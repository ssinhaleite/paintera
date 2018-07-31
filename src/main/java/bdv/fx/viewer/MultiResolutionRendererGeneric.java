/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2016 Tobias Pietzsch, Stephan Saalfeld, Stephan Preibisch,
 * Jean-Yves Tinevez, HongKee Moon, Johannes Schindelin, Curtis Rueden, John Bogovic
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.fx.viewer;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import bdv.cache.CacheControl;
import bdv.util.MipmapTransforms;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.SourceAndConverter;
import bdv.viewer.render.AccumulateProjectorFactory;
import bdv.viewer.render.DefaultMipmapOrdering;
import bdv.viewer.render.EmptyProjector;
import bdv.viewer.render.MipmapOrdering;
import bdv.viewer.render.MipmapOrdering.Level;
import bdv.viewer.render.MipmapOrdering.MipmapHints;
import bdv.viewer.render.Prefetcher;
import bdv.viewer.render.VolatileHierarchyProjector;
import bdv.viewer.render.VolatileProjector;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.cache.iotiming.CacheIoTiming;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converter;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.IntAccess;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.Renderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tmp.bdv.img.cache.VolatileCachedCellImg;

/**
 * A {@link Renderer} that uses a coarse-to-fine rendering scheme. First, a small data store at a fraction of the canvas
 * resolution is rendered. Then, increasingly larger images are rendered, until the full canvas resolution is reached.
 * <p>
 * When drawing the low-resolution data store to the screen, they will be scaled up by Java2D to the full canvas size,
 * which is relatively fast. Rendering the small, low-resolution images is usually very fast, such that the display is
 * very interactive while the user changes the viewing transformation for example. When the transformation remains fixed
 * for a longer period, higher-resolution details are filled in successively.
 * <p>
 * The renderer allocates a data store for each of a predefined set of
 * <em>screen scales</em> (a screen scale of 1 means that 1 pixel in the screen
 * image is displayed as 1 pixel on the canvas, a screen scale of 0.5 means 1 pixel in the screen image is displayed as
 * 2 pixel on the canvas, etc.)
 * <p>
 * At any time, one of these screen scales is selected as the <em>highest screen scale</em>. Rendering starts with this
 * highest screen scale and then proceeds to lower screen scales (higher resolution images). Unless the highest screen
 * scale is currently rendering, {@link #requestRepaint() repaint request} will cancel rendering, such that display
 * remains interactive.
 * <p>
 * The renderer tries to maintain a per-frame rendering time close to a desired number of <code>targetRenderNanos</code>
 * nanoseconds. If the rendering time (in nanoseconds) for the (currently) highest scaled screen image is above this
 * threshold, a coarser screen scale is chosen as the highest screen scale to use. Similarly, if the rendering time for
 * the (currently) second-highest scaled screen image is below this threshold, this finer screen scale chosen as the
 * highest screen scale to use.
 * <p>
 * The renderer uses multiple threads (if desired) and double-buffering (if desired).
 * <p>
 * Double buffering means that three render stores are created for every screen scale. After rendering the first one of
 * them and setting it to the {@link RenderTargetGeneric}, next time, rendering goes to the second one, then to the
 * third. The {@link RenderTargetGeneric} will always have a complete image, which is not rendered to while it is
 * potentially drawn to the screen. When setting an image to the {@link RenderTargetGeneric}, the {@link
 * RenderTargetGeneric} will release one of the previously set images to be rendered again. Thus, rendering will not
 * interfere with painting the data to the canvas.
 * <p>
 * The renderer supports rendering of {@link Volatile} sources. In each rendering pass, all currently valid data for the
 * best fitting mipmap level and all coarser levels is rendered to a {@link #renderImages temporary image} for each
 * visible source. Then the temporary images are combined to the final image for display. The number of passes required
 * until all data is valid might differ between visible sources.
 * <p>
 * Rendering timing is tied to a {@link CacheControl} control for IO budgeting, etc.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 * @author Philipp Hanslovsky
 */
public class MultiResolutionRendererGeneric<T>
{

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.class);

	public static interface ImageGenerator<T>
	{

		public T create(int width, int height);

		public T create(int width, int height, T other);

	}

	/**
	 * Receiver for the data store that we render.
	 */
	protected final TransformAwareRenderTargetGeneric<T> display;

	/**
	 * Thread that triggers repainting of the display. Requests for repainting are send there.
	 */
	protected final PainterThread painterThread;

	/**
	 * Currently active projector, used to re-paint the display. It maps the source data to {@link #screenImages}.
	 */
	protected VolatileProjector projector;

	/**
	 * The index of the screen scale of the {@link #projector current projector}.
	 */
	protected int currentScreenScaleIndex;

	/**
	 * Whether double buffering is used.
	 */
	protected final boolean doubleBuffered;

	/**
	 * Double-buffer index of next {@link #screenImages image} to render.
	 */
	protected final ArrayDeque<Integer> renderIdQueue;

	/**
	 * Maps from data store to double-buffer index. Needed for double-buffering.
	 */
	protected final HashMap<T, Integer> bufferedImageToRenderId;

	/**
	 * Used to render an individual source. One image per screen resolution and visible source. First index is screen
	 * scale, second index is index in list of visible sources.
	 */
	protected ArrayImg<ARGBType, IntArray>[][] renderImages;

	/**
	 * Storage for mask images of {@link VolatileHierarchyProjector}. One array per visible source. (First) index is
	 * index in list of visible sources.
	 */
	protected byte[][] renderMaskArrays;

	/**
	 * Used to render the image for display. Three images per screen resolution if double buffering is enabled. First
	 * index is screen scale, second index is double-buffer.
	 */
	protected T[][] screenImages;

	/**
	 * data store wrapping the data in the {@link #screenImages}. First index is screen scale, second index is
	 * double-buffer.
	 */
	protected T[][] bufferedImages;

	/**
	 * Scale factors from the {@link #display viewer canvas} to the {@link #screenImages}.
	 * <p>
	 * A scale factor of 1 means 1 pixel in the screen image is displayed as 1 pixel on the canvas, a scale factor of
	 * 0.5 means 1 pixel in the screen image is displayed as 2 pixel on the canvas, etc.
	 */
	protected final double[] screenScales;

	/**
	 * The scale transformation from viewer to {@link #screenImages screen image}. Each transformations corresponds
	 * to a
	 * {@link #screenScales screen scale}.
	 */
	protected AffineTransform3D[] screenScaleTransforms;

	/**
	 * If the rendering time (in nanoseconds) for the (currently) highest scaled screen image is above this threshold,
	 * increase the {@link #maxScreenScaleIndex index} of the highest screen scale to use. Similarly, if the rendering
	 * time for the (currently) second-highest scaled screen image is below this threshold, decrease the {@link
	 * #maxScreenScaleIndex index} of the highest screen scale to use.
	 */
	protected final long targetRenderNanos;

	/**
	 * The index of the (coarsest) screen scale with which to start rendering. Once this level is painted, rendering
	 * proceeds to lower screen scales until index 0 (full resolution) has been reached. While rendering, the
	 * maxScreenScaleIndex is adapted such that it is the highest index for which rendering in {@link
	 * #targetRenderNanos} nanoseconds is still possible.
	 */
	protected int maxScreenScaleIndex;

	/**
	 * The index of the screen scale which should be rendered next.
	 */
	protected int requestedScreenScaleIndex;

	/**
	 * Whether the current rendering operation may be cancelled (to start a new one). Rendering may be cancelled unless
	 * we are rendering at coarsest screen scale and coarsest mipmap level.
	 */
	protected volatile boolean renderingMayBeCancelled;

	/**
	 * How many threads to use for rendering.
	 */
	protected final int numRenderingThreads;

	/**
	 * {@link ExecutorService} used for rendering.
	 */
	protected final ExecutorService renderingExecutorService;

	/**
	 * TODO
	 */
	protected final AccumulateProjectorFactory<ARGBType> accumulateProjectorFactory;

	/**
	 * Controls IO budgeting and fetcher queue.
	 */
	protected final CacheControl cacheControl;

	/**
	 * Whether volatile versions of sources should be used if available.
	 */
	protected final boolean useVolatileIfAvailable;

	/**
	 * Whether a repaint was {@link #requestRepaint() requested}. This will cause {@link
	 * CacheControl#prepareNextFrame()}.
	 */
	protected boolean newFrameRequest;

	/**
	 * The timepoint for which last a projector was {@link #createProjector created}.
	 */
	protected int previousTimepoint;

	// TODO: should be settable
	protected long[] iobudget = new long[] {100l * 1000000l, 10l * 1000000l};

	// TODO: should be settable
	protected boolean prefetchCells = true;

	private final Function<T, ArrayImg<ARGBType, ? extends IntAccess>> wrapAsArrayImg;

	private final ToIntFunction<T> width;

	private final ToIntFunction<T> height;

	private final ImageGenerator<T> makeImage;

	/**
	 * @param display
	 * 		The canvas that will display the images we render.
	 * @param painterThread
	 * 		Thread that triggers repainting of the display. Requests for repainting are send there.
	 * @param screenScales
	 * 		Scale factors from the viewer canvas to screen images of different resolutions. A scale factor of 1 means 1
	 * 		pixel in the screen image is displayed as 1 pixel on the canvas, a scale factor of 0.5 means 1 pixel in the
	 * 		screen image is displayed as 2 pixel on the canvas, etc.
	 * @param targetRenderNanos
	 * 		Target rendering time in nanoseconds. The rendering time for the coarsest rendered scale should be below
	 * 		this
	 * 		threshold.
	 * @param doubleBuffered
	 * 		Whether to use double buffered rendering.
	 * @param numRenderingThreads
	 * 		How many threads to use for rendering.
	 * @param renderingExecutorService
	 * 		if non-null, this is used for rendering. Note, that it is still important to supply the numRenderingThreads
	 * 		parameter, because that is used to determine into how many sub-tasks rendering is split.
	 * @param useVolatileIfAvailable
	 * 		whether volatile versions of sources should be used if available.
	 * @param accumulateProjectorFactory
	 * 		can be used to customize how sources are combined.
	 * @param cacheControl
	 * 		the cache controls IO budgeting and fetcher queue.
	 */
	@SuppressWarnings("unchecked")
	public MultiResolutionRendererGeneric(
			final TransformAwareRenderTargetGeneric<T> display,
			final PainterThread painterThread,
			final double[] screenScales,
			final long targetRenderNanos,
			final boolean doubleBuffered,
			final int numRenderingThreads,
			final ExecutorService renderingExecutorService,
			final boolean useVolatileIfAvailable,
			final AccumulateProjectorFactory<ARGBType> accumulateProjectorFactory,
			final CacheControl cacheControl,
			final Function<T, ArrayImg<ARGBType, ? extends IntAccess>> wrapAsArrayImg,
			final ImageGenerator<T> makeImage,
			final Class<? extends T> cls,
			final ToIntFunction<T> width,
			final ToIntFunction<T> height)
	{
		this.display = display;
		this.painterThread = painterThread;
		projector = null;
		currentScreenScaleIndex = -1;
		this.screenScales = screenScales.clone();
		this.doubleBuffered = doubleBuffered;
		renderIdQueue = new ArrayDeque<>();
		bufferedImageToRenderId = new HashMap<>();
		renderImages = new ArrayImg[screenScales.length][0];
		// new ARGBScreenImage[ screenScales.length ][ 0 ];
		renderMaskArrays = new byte[0][];
		screenImages = (T[][]) Array.newInstance(cls, screenScales.length, 3);
		// )new ARGBScreenImage[ screenScales.length ][ 3 ];
		this.bufferedImages = (T[][]) Array.newInstance(cls, screenScales.length, 3);
		screenScaleTransforms = new AffineTransform3D[screenScales.length];

		this.makeImage = makeImage;

		this.width = width;

		this.height = height;

		this.wrapAsArrayImg = wrapAsArrayImg;

		this.targetRenderNanos = targetRenderNanos;

		maxScreenScaleIndex = screenScales.length - 1;
		requestedScreenScaleIndex = maxScreenScaleIndex;
		renderingMayBeCancelled = true;
		this.numRenderingThreads = numRenderingThreads;
		this.renderingExecutorService = renderingExecutorService;
		this.useVolatileIfAvailable = useVolatileIfAvailable;
		this.accumulateProjectorFactory = accumulateProjectorFactory;
		this.cacheControl = cacheControl;
		newFrameRequest = false;
		previousTimepoint = -1;
	}

	/**
	 * Check whether the size of the display component was changed and recreate {@link #screenImages} and {@link
	 * #screenScaleTransforms} accordingly.
	 *
	 * @return whether the size was changed.
	 */
	protected synchronized boolean checkResize()
	{
		final int componentW = display.getWidth();
		final int componentH = display.getHeight();
		if (screenImages[0][0] == null
				|| width.applyAsInt(screenImages[0][0]) * screenScales[0] != componentW
				|| height.applyAsInt(screenImages[0][0]) * screenScales[0] != componentH)
		{
			renderIdQueue.clear();
			renderIdQueue.addAll(Arrays.asList(0, 1, 2));
			bufferedImageToRenderId.clear();
			for (int i = 0; i < screenScales.length; ++i)
			{
				final double screenToViewerScale = screenScales[i];
				final int    w                   = (int) (screenToViewerScale * componentW);
				final int    h                   = (int) (screenToViewerScale * componentH);
				if (doubleBuffered)
					for (int b = 0; b < 3; ++b)
					{
						// reuse storage arrays of level 0 (highest resolution)
						screenImages[i][b] = i == 0
						                     ? makeImage.create(w, h)
						                     : makeImage.create(w, h, screenImages[0][b]);
						final T bi = screenImages[i][b];
						// getBufferedImage.apply( screenImages[ i ][ b ] );
						bufferedImages[i][b] = bi;
						bufferedImageToRenderId.put(bi, b);
					}
				else
				{
					screenImages[i][0] = makeImage.create(w, h);
					bufferedImages[i][0] = screenImages[i][0];
					// getBufferedImage.apply( screenImages[ i ][ 0 ] );
				}
				final AffineTransform3D scale  = new AffineTransform3D();
				final double            xScale = (double) w / componentW;
				final double            yScale = (double) h / componentH;
				scale.set(xScale, 0, 0);
				scale.set(yScale, 1, 1);
				scale.set(0.5 * xScale - 0.5, 0, 3);
				scale.set(0.5 * yScale - 0.5, 1, 3);
				screenScaleTransforms[i] = scale;
			}

			return true;
		}
		return false;
	}

	@SuppressWarnings("unchecked")
	protected boolean checkRenewRenderImages(final int numVisibleSources)
	{
		final int n = numVisibleSources > 1 ? numVisibleSources : 0;
		if (n != renderImages[0].length ||
				n != 0 &&
						(renderImages[0][0].dimension(0) != width.applyAsInt(screenImages[0][0]) ||
								renderImages[0][0].dimension(1) != height.applyAsInt(screenImages[0][0])))
		{
			renderImages = new ArrayImg[screenScales.length][n];
			for (int i = 0; i < screenScales.length; ++i)
			{
				final int w = width.applyAsInt(screenImages[i][0]);
				final int h = height.applyAsInt(screenImages[i][0]);
				for (int j = 0; j < n; ++j)
					renderImages[i][j] = i == 0
					                     ? ArrayImgs.argbs(w, h)
					                     : ArrayImgs.argbs(renderImages[0][j].update(null), w, h);
			}
			return true;
		}
		return false;
	}

	protected boolean checkRenewMaskArrays(final int numVisibleSources)
	{
		final int size = width.applyAsInt(screenImages[0][0]) * height.applyAsInt(screenImages[0][0]);
		if (numVisibleSources != renderMaskArrays.length ||
				numVisibleSources != 0 && renderMaskArrays[0].length < size)
		{
			renderMaskArrays = new byte[numVisibleSources][];
			for (int j = 0; j < numVisibleSources; ++j)
				renderMaskArrays[j] = new byte[size];
			return true;
		}
		return false;
	}

	protected final AffineTransform3D currentProjectorTransform = new AffineTransform3D();

	/**
	 * Render image at the {@link #requestedScreenScaleIndex requested screen scale}.
	 */
	public boolean paint(
			final List<SourceAndConverter<?>> sources,
			final int timepoint,
			final AffineTransform3D viewerTransform,
			final Function<Source<?>, Interpolation> interpolationForSource,
			final Object synchronizationLock)
	{
		if (display.getWidth() <= 0 || display.getHeight() <= 0)
			return false;

		final boolean resized = checkResize();

		// the BufferedImage that is rendered to (to paint to the canvas)
		final T bufferedImage;

		// the projector that paints to the screenImage.
		final VolatileProjector p;

		final boolean clearQueue;

		final boolean createProjector;

		synchronized (this)
		{
			// Rendering may be cancelled unless we are rendering at coarsest
			// screen scale and coarsest mipmap level.
			renderingMayBeCancelled = requestedScreenScaleIndex < maxScreenScaleIndex;

			clearQueue = newFrameRequest;
			if (clearQueue)
				cacheControl.prepareNextFrame();
			createProjector = newFrameRequest || resized || requestedScreenScaleIndex != currentScreenScaleIndex;
			newFrameRequest = false;

			final List<SourceAndConverter<?>> sacs = sources;

			if (createProjector)
			{
				final int renderId = renderIdQueue.peek();
				currentScreenScaleIndex = requestedScreenScaleIndex;
				bufferedImage = bufferedImages[currentScreenScaleIndex][renderId];
				final T screenImage = screenImages[currentScreenScaleIndex][renderId];
				synchronized (Optional.ofNullable(synchronizationLock).orElse(this))
				{
					final int numSources = sacs.size();
					checkRenewRenderImages(numSources);
					checkRenewMaskArrays(numSources);
					final int t = timepoint;
					p = createProjector(
							sacs,
							t,
							viewerTransform,
							currentScreenScaleIndex,
							wrapAsArrayImg.apply(screenImage),
							interpolationForSource
					                   );
				}
				projector = p;
			}
			else
			{
				bufferedImage = null;
				p = projector;
			}

			requestedScreenScaleIndex = 0;
		}

		// try rendering
		final boolean success    = p.map(createProjector);
		final long    rendertime = p.getLastFrameRenderNanoTime();

		synchronized (this)
		{
			// if rendering was not cancelled...
			if (success)
			{
				if (createProjector)
				{
					final T bi = display.setBufferedImageAndTransform(bufferedImage, currentProjectorTransform);
					if (doubleBuffered)
					{
						renderIdQueue.pop();
						final Integer id = bufferedImageToRenderId.get(bi);
						if (id != null)
							renderIdQueue.add(id);
					}

					if (currentScreenScaleIndex == maxScreenScaleIndex)
					{
						if (rendertime > targetRenderNanos && maxScreenScaleIndex < screenScales.length - 1)
							maxScreenScaleIndex++;
						else if (rendertime < targetRenderNanos / 3 && maxScreenScaleIndex > 0)
							maxScreenScaleIndex--;
					}
					else if (currentScreenScaleIndex == maxScreenScaleIndex - 1)
						if (rendertime < targetRenderNanos && maxScreenScaleIndex > 0)
							maxScreenScaleIndex--;
				}

				if (currentScreenScaleIndex > 0)
					requestRepaint(currentScreenScaleIndex - 1);
				else if (!p.isValid())
				{
					try
					{
						Thread.sleep(1);
					} catch (final InterruptedException e)
					{
						// restore interrupted state
						Thread.currentThread().interrupt();
					}
					requestRepaint(currentScreenScaleIndex);
				}
			}
		}

		return success;
	}

	/**
	 * Request a repaint of the display from the painter thread, with maximum screen scale index and mipmap level.
	 */
	public void requestRepaint()
	{
		newFrameRequest = true;
		requestRepaint(maxScreenScaleIndex);
	}

	/**
	 * Request a repaint of the display from the painter thread. The painter thread will trigger a {@link #paint} as
	 * soon as possible (that is, immediately or after the currently running {@link #paint} has completed).
	 */
	public synchronized void requestRepaint(final int screenScaleIndex)
	{
		if (renderingMayBeCancelled && projector != null)
			projector.cancel();
		if (screenScaleIndex > requestedScreenScaleIndex)
			requestedScreenScaleIndex = screenScaleIndex;
		painterThread.requestRepaint();
	}

	/**
	 * DON'T USE THIS.
	 * <p>
	 * This is a work around for JDK bug https://bugs.openjdk.java.net/browse/JDK-8029147 which leads to ViewerPanel
	 * not
	 * being garbage-collected when ViewerFrame is closed. So instead we need to manually let go of resources...
	 */
	public void kill()
	{
		projector = null;
		renderIdQueue.clear();
		bufferedImageToRenderId.clear();
		for (int i = 0; i < renderImages.length; ++i)
			renderImages[i] = null;
		for (int i = 0; i < renderMaskArrays.length; ++i)
			renderMaskArrays[i] = null;
		for (int i = 0; i < screenImages.length; ++i)
			screenImages[i] = null;
		for (int i = 0; i < bufferedImages.length; ++i)
			bufferedImages[i] = null;
	}

	private VolatileProjector createProjector(
			final List<SourceAndConverter<?>> sacs,
			final int timepoint,
			final AffineTransform3D viewerTransform,
			final int screenScaleIndex,
			final ArrayImg<ARGBType, ? extends IntAccess> screenImage,
			final Function<Source<?>, Interpolation> interpolationForSource)
	{
		/*
		 * This shouldn't be necessary, with
		 * CacheHints.LoadingStrategy==VOLATILE
		 */
		//		CacheIoTiming.getIoTimeBudget().clear(); // clear time budget such that prefetching doesn't wait for
		// loading blocks.
		VolatileProjector projector;
		if (sacs.isEmpty())
			projector = new EmptyProjector<>(screenImage);
		else if (sacs.size() == 1)
		{
			LOG.debug("Got only one source, creating pre-multiplying single source projector");
			final SourceAndConverter<?> sac           = sacs.get(0);
			final Interpolation         interpolation = interpolationForSource.apply(sac.getSpimSource());
			projector = createSingleSourceProjector(
					sac,
					timepoint,
					viewerTransform,
					currentScreenScaleIndex,
					screenImage,
					renderMaskArrays[0],
					interpolation,
					true
			                                       );
		}
		else
		{
			LOG.debug("Got {} sources, creating {} non-pre-multiplying single source projectors", sacs.size());
			final ArrayList<VolatileProjector>            sourceProjectors = new ArrayList<>();
			final ArrayList<ArrayImg<ARGBType, IntArray>> sourceImages     = new ArrayList<>();
			final ArrayList<Source<?>>                    sources          = new ArrayList<>();
			int                                           j                = 0;
			for (final SourceAndConverter<?> sac : sacs)
			{
				final ArrayImg<ARGBType, IntArray> renderImage = renderImages[currentScreenScaleIndex][j];
				final byte[]                       maskArray   = renderMaskArrays[j];
				++j;
				final Interpolation interpolation = interpolationForSource.apply(sac.getSpimSource());
				final VolatileProjector p = createSingleSourceProjector(
						sac,
						timepoint,
						viewerTransform,
						currentScreenScaleIndex,
						renderImage,
						maskArray,
						interpolation,
						false
				                                                       );
				sourceProjectors.add(p);
				sources.add(sac.getSpimSource());
				sourceImages.add(renderImage);
			}
			projector = accumulateProjectorFactory.createAccumulateProjector(
					sourceProjectors,
					sources,
					sourceImages,
					screenImage,
					numRenderingThreads,
					renderingExecutorService
			                                                                );
		}
		previousTimepoint = timepoint;
		currentProjectorTransform.set(viewerTransform);
		CacheIoTiming.getIoTimeBudget().reset(iobudget);
		return projector;
	}

	private static class SimpleVolatileProjector<A> extends SimpleInterruptibleProjectorPreMultiply<A>
			implements VolatileProjector
	{
		private boolean valid = false;

		public SimpleVolatileProjector(
				final RandomAccessible<A> source,
				final Converter<? super A, ARGBType> converter,
				final RandomAccessibleInterval<ARGBType> target,
				final int numThreads,
				final ExecutorService executorService)
		{
			super(source, converter, target, numThreads, executorService);
		}

		@Override
		public boolean map(final boolean clearUntouchedTargetPixels)
		{
			final boolean success = super.map();
			valid |= success;
			return success;
		}

		@Override
		public boolean isValid()
		{
			return valid;
		}
	}

	private <U> VolatileProjector createSingleSourceProjector(
			final SourceAndConverter<U> source,
			final int timepoint,
			final AffineTransform3D viewerTransform,
			final int screenScaleIndex,
			final ArrayImg<ARGBType, ? extends IntAccess> screenImage,
			final byte[] maskArray,
			final Interpolation interpolation,
			final boolean preMultiply)
	{
		if (useVolatileIfAvailable)
			if (source.asVolatile() != null)
			{
				LOG.debug(
						"Volatile is available for source={} (name={})",
						source.getSpimSource(),
						source.getSpimSource().getName()
				         );
				return createSingleSourceVolatileProjector(
						source.asVolatile(),
						timepoint,
						screenScaleIndex,
						viewerTransform,
						screenImage,
						maskArray,
						interpolation,
						preMultiply
				                                          );
			}
			else if (source.getSpimSource().getType() instanceof Volatile)
			{
				LOG.debug(
						"Casting to volatile source:{} (name={})",
						source.getSpimSource(),
						source.getSpimSource().getName()
				         );
				@SuppressWarnings("unchecked") final SourceAndConverter<? extends Volatile<?>> vsource =
						(SourceAndConverter<? extends Volatile<?>>) source;
				return createSingleSourceVolatileProjector(
						vsource,
						timepoint,
						screenScaleIndex,
						viewerTransform,
						screenImage,
						maskArray,
						interpolation,
						preMultiply
				                                          );
			}

		final AffineTransform3D screenScaleTransform = screenScaleTransforms[currentScreenScaleIndex];
		final AffineTransform3D screenTransform      = viewerTransform.copy();
		screenTransform.preConcatenate(screenScaleTransform);
		final int bestLevel = MipmapTransforms.getBestMipMapLevel(screenTransform, source.getSpimSource(), timepoint);
		LOG.debug("Using bestLevel={}", bestLevel);
		return new SimpleVolatileProjector<>(
				getTransformedSource(
						source.getSpimSource(),
						timepoint,
						viewerTransform,
						screenScaleTransform,
						bestLevel,
						null,
						interpolation
				                    ),
				source.getConverter(), screenImage, numRenderingThreads, renderingExecutorService
		);
	}

	private <V extends Volatile<?>> VolatileProjector createSingleSourceVolatileProjector(
			final SourceAndConverter<V> source,
			final int t,
			final int screenScaleIndex,
			final AffineTransform3D viewerTransform,
			final ArrayImg<ARGBType, ? extends IntAccess> screenImage,
			final byte[] maskArray,
			final Interpolation interpolation,
			final boolean preMultiply)
	{
		LOG.debug(
				"Creating single source volatile projector for source={} (name={})",
				source.getSpimSource(),
				source.getSpimSource().getName()
		         );
		final AffineTransform3D              screenScaleTransform = screenScaleTransforms[currentScreenScaleIndex];
		final ArrayList<RandomAccessible<V>> renderList           = new ArrayList<>();
		final Source<V>                      spimSource           = source.getSpimSource();
		LOG.debug("Creating single source volatile projector for type={}", spimSource.getType());

		final MipmapOrdering ordering = MipmapOrdering.class.isInstance(spimSource)
		                                ? (MipmapOrdering) spimSource
		                                : new DefaultMipmapOrdering(spimSource);

		final AffineTransform3D screenTransform = viewerTransform.copy();
		screenTransform.preConcatenate(screenScaleTransform);
		final MipmapHints hints  = ordering.getMipmapHints(screenTransform, t, previousTimepoint);
		final List<Level> levels = hints.getLevels();

		if (prefetchCells)
		{
			Collections.sort(levels, MipmapOrdering.prefetchOrderComparator);
			for (final Level l : levels)
			{
				final CacheHints cacheHints = l.getPrefetchCacheHints();
				if (cacheHints == null || cacheHints.getLoadingStrategy() != LoadingStrategy.DONTLOAD)
					prefetch(
							spimSource,
							t,
							viewerTransform,
							screenScaleTransform,
							l.getMipmapLevel(),
							cacheHints,
							screenImage,
							interpolation
					        );
			}
		}

		Collections.sort(levels, MipmapOrdering.renderOrderComparator);
		for (final Level l : levels)
			renderList.add(getTransformedSource(
					spimSource,
					t,
					viewerTransform,
					screenScaleTransform,
					l.getMipmapLevel(),
					l.getRenderCacheHints(),
					interpolation
			                                   ));

		if (hints.renewHintsAfterPaintingOnce())
			newFrameRequest = true;

		LOG.debug("Creating projector. Pre-multiply? {}", preMultiply);

		if (preMultiply)
			return new VolatileHierarchyProjectorPreMultiply<>(
					renderList,
					source.getConverter(),
					screenImage,
					maskArray,
					numRenderingThreads,
					renderingExecutorService
			);
		else
			return new VolatileHierarchyProjector<>(
					renderList,
					source.getConverter(),
					screenImage,
					maskArray,
					numRenderingThreads,
					renderingExecutorService
			);
	}

	private static <T> RandomAccessible<T> getTransformedSource(
			final Source<T> source,
			final int timepoint,
			final AffineTransform3D viewerTransform,
			final AffineTransform3D screenScaleTransform,
			final int mipmapIndex,
			final CacheHints cacheHints,
			final Interpolation interpolation)
	{

		final RandomAccessibleInterval<T> img = source.getSource(timepoint, mipmapIndex);
		if (VolatileCachedCellImg.class.isInstance(img))
			((VolatileCachedCellImg<?, ?>) img).setCacheHints(cacheHints);

		final RealRandomAccessible<T> ipimg = source.getInterpolatedSource(timepoint, mipmapIndex, interpolation);

		final AffineTransform3D sourceToScreen  = viewerTransform.copy();
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		source.getSourceTransform(timepoint, mipmapIndex, sourceTransform);
		sourceToScreen.concatenate(sourceTransform);
		sourceToScreen.preConcatenate(screenScaleTransform);

		LOG.debug(
				"Getting transformed source {} (name={}) for t={} level={} transform={} screen-scale={} hints={} " +
						"interpolation={}",
				source,
				source.getName(),
				timepoint,
				mipmapIndex,
				sourceToScreen,
				screenScaleTransform,
				cacheHints,
				interpolation
		         );

		return RealViews.affine(ipimg, sourceToScreen);
	}

	private static <T> void prefetch(
			final Source<T> source,
			final int timepoint,
			final AffineTransform3D viewerTransform,
			final AffineTransform3D screenScaleTransform,
			final int mipmapIndex,
			final CacheHints prefetchCacheHints,
			final Dimensions screenInterval,
			final Interpolation interpolation)
	{
		final RandomAccessibleInterval<T> img = source.getSource(timepoint, mipmapIndex);
		if (VolatileCachedCellImg.class.isInstance(img))
		{
			final VolatileCachedCellImg<?, ?> cellImg = (VolatileCachedCellImg<?, ?>) img;

			CacheHints hints = prefetchCacheHints;
			if (hints == null)
			{
				final CacheHints d = cellImg.getDefaultCacheHints();
				hints = new CacheHints(LoadingStrategy.VOLATILE, d.getQueuePriority(), false);
			}
			cellImg.setCacheHints(hints);
			final int[] cellDimensions = new int[3];
			cellImg.getCellGrid().cellDimensions(cellDimensions);
			final long[] dimensions = new long[3];
			cellImg.dimensions(dimensions);
			final RandomAccess<?> cellsRandomAccess = cellImg.getCells().randomAccess();

			final AffineTransform3D sourceToScreen  = viewerTransform.copy();
			final AffineTransform3D sourceTransform = new AffineTransform3D();
			source.getSourceTransform(timepoint, mipmapIndex, sourceTransform);
			sourceToScreen.concatenate(sourceTransform);
			sourceToScreen.preConcatenate(screenScaleTransform);

			Prefetcher.fetchCells(
					sourceToScreen,
					cellDimensions,
					dimensions,
					screenInterval,
					interpolation,
					cellsRandomAccess
			                     );
		}
	}

}
