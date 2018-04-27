package org.janelia.saalfeldlab.paintera.ui.opendialog;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;

import org.janelia.saalfeldlab.fx.ui.ExceptionNode;
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread;
import org.janelia.saalfeldlab.n5.ByteArrayDataBlock;
import org.janelia.saalfeldlab.n5.DataBlock;
import org.janelia.saalfeldlab.n5.DataType;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.GzipCompression;
import org.janelia.saalfeldlab.n5.LongArrayDataBlock;
import org.janelia.saalfeldlab.n5.N5Reader;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentOnlyLocal;
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignmentState;
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource;
import org.janelia.saalfeldlab.paintera.id.IdService;
import org.janelia.saalfeldlab.paintera.id.N5IdService;
import org.janelia.saalfeldlab.util.MakeUnchecked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bdv.util.volatiles.SharedQueue;
import bdv.util.volatiles.VolatileRandomAccessibleIntervalView;
import bdv.util.volatiles.VolatileViews;
import gnu.trove.set.hash.TLongHashSet;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableStringValue;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import net.imglib2.Cursor;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.Volatile;
import net.imglib2.algorithm.util.Grids;
import net.imglib2.cache.img.CachedCellImg;
import net.imglib2.cache.ref.BoundedSoftRefLoaderCache;
import net.imglib2.cache.ref.SoftRefLoaderCache;
import net.imglib2.cache.util.LoaderCacheAsCacheAdapter;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converters;
import net.imglib2.img.cell.Cell;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.Scale3D;
import net.imglib2.realtransform.Translation3D;
import net.imglib2.type.NativeType;
import net.imglib2.type.label.FromIntegerTypeConverter;
import net.imglib2.type.label.Label;
import net.imglib2.type.label.LabelMultisetType;
import net.imglib2.type.label.LabelMultisetTypeDownscaler;
import net.imglib2.type.label.LabelUtils;
import net.imglib2.type.label.N5CacheLoader;
import net.imglib2.type.label.VolatileLabelMultisetArray;
import net.imglib2.type.label.VolatileLabelMultisetType;
import net.imglib2.type.numeric.IntegerType;
import net.imglib2.type.numeric.integer.UnsignedLongType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Pair;
import net.imglib2.util.Triple;
import net.imglib2.util.ValueTriple;
import net.imglib2.view.IntervalView;
import net.imglib2.view.Views;

public class GenericBackendDialogN5 implements SourceFromRAI
{

	private static final String EMPTY_STRING = "";

	private static final String LABEL_MULTISETTYPE_KEY = "isLabelMultiset";

	private static final Logger LOG = LoggerFactory.getLogger( MethodHandles.lookup().lookupClass() );

	private static final String RESOLUTION_KEY = "resolution";

	private static final String OFFSET_KEY = "offset";

	private static final String MIN_KEY = "min";

	private static final String MAX_KEY = "max";

	private static final String DOWNSAMPLING_FACTORS_KEY = "downsamplingFactors";

	private static final String MAX_NUM_ENTRIES_KEY = "maxNumEntries";

	private static final String ERROR_MESSAGE_PATTERN = "n5? %s -- dataset? %s -- update? %s";

	private final DatasetInfo datasetInfo = new DatasetInfo();

	private final SimpleObjectProperty< Supplier< N5Writer > > n5Supplier = new SimpleObjectProperty<>( () -> null );

	private final ObjectBinding< N5Writer > n5 = Bindings.createObjectBinding( () -> Optional.ofNullable( n5Supplier.get() ).map( Supplier::get ).orElse( null ), n5Supplier );

	private final StringProperty dataset = new SimpleStringProperty();

	private final ArrayList< Thread > directoryTraversalThreads = new ArrayList<>();

	private final SimpleBooleanProperty isTraversingDirectories = new SimpleBooleanProperty();

	private final BooleanBinding isN5Valid = n5.isNotNull();

	private final BooleanBinding isDatasetValid = dataset.isNotNull().and( dataset.isNotEqualTo( EMPTY_STRING ) );

	private final SimpleBooleanProperty datasetUpdateFailed = new SimpleBooleanProperty( false );

	private final BooleanBinding isReady = isN5Valid
			.and( isDatasetValid )
			.and( datasetUpdateFailed.not() );

	{
		isN5Valid.addListener( ( obs, oldv, newv ) -> datasetUpdateFailed.set( false ) );
	}

	private final StringBinding errorMessage = Bindings.createStringBinding(
			() -> isReady.get() ? null : String.format( ERROR_MESSAGE_PATTERN, isN5Valid.get(), isDatasetValid.get(), datasetUpdateFailed.not().get() ),
			isReady );

	private final StringBinding name = Bindings.createStringBinding( () -> {
		final String[] entries = Optional
				.ofNullable( dataset.get() )
				.map( d -> d.split( "/" ) )
				.map( a -> a.length > 0 ? a : new String[] { null } )
				.orElse( new String[] { null } );
		return entries[ entries.length - 1 ];
	}, dataset );

	private final ObservableList< String > datasetChoices = FXCollections.observableArrayList();

	private final String identifier;

	private final Node node;

	public GenericBackendDialogN5(
			final Node n5RootNode,
			final Consumer< Event > onBrowseClicked,
			final String identifier,
			final ObservableValue< Supplier< N5Writer > > writerSupplier )
	{
		this( "dataset", n5RootNode, onBrowseClicked, identifier, writerSupplier );
	}

	public GenericBackendDialogN5(
			final String datasetPrompt,
			final Node n5RootNode,
			final Consumer< Event > onBrowseClicked,
			final String identifier,
			final ObservableValue< Supplier< N5Writer > > writerSupplier )
	{
		this.identifier = identifier;
		this.node = initializeNode( n5RootNode, datasetPrompt, onBrowseClicked );
		n5Supplier.bind( writerSupplier );
		n5.addListener( ( obs, oldv, newv ) -> {
			LOG.debug( "Updated n5: obs={} oldv={} newv={}", obs, oldv, newv );
			if ( newv == null )
			{
				datasetChoices.clear();
				return;
			}

			LOG.debug( "Updating dataset choices!" );
			synchronized ( directoryTraversalThreads )
			{
				this.isTraversingDirectories.set( false );
				directoryTraversalThreads.forEach( Thread::interrupt );
				directoryTraversalThreads.clear();
				final Thread t = new Thread( () -> {
					this.isTraversingDirectories.set( true );
					final AtomicBoolean discardDatasetList = new AtomicBoolean( false );
					try
					{
						final List< String > datasets = N5Helpers.discoverDatasets( newv, () -> discardDatasetList.set( true ) );
						if ( !Thread.currentThread().isInterrupted() && !discardDatasetList.get() )
						{
							LOG.debug( "Found these datasets: {}", datasets );
							InvokeOnJavaFXApplicationThread.invoke( () -> datasetChoices.setAll( datasets ) );
							if ( !newv.equals( oldv ) )
								InvokeOnJavaFXApplicationThread.invoke( () -> this.dataset.set( null ) );
						}
					}
					finally
					{
						this.isTraversingDirectories.set( false );
					}
				} );
				directoryTraversalThreads.add( t );
				t.start();
			}
		} );
		dataset.addListener( ( obs, oldv, newv ) -> Optional.ofNullable( newv ).filter( v -> v.length() > 0 ).ifPresent( v -> updateDatasetInfo( v, this.datasetInfo ) ) );

		this.isN5Valid.addListener( ( obs, oldv, newv ) -> {
			synchronized ( directoryTraversalThreads )
			{
				directoryTraversalThreads.forEach( Thread::interrupt );
				directoryTraversalThreads.clear();
			}
		} );

		dataset.set( "" );
	}

	public void updateDatasetInfo( final String dataset, final DatasetInfo info )
	{

		LOG.debug( "Updating dataset info for dataset {}", dataset );
		try
		{
			final N5Reader n5 = this.n5.get();
			final String ds = n5.datasetExists( dataset ) ? dataset : Paths.get( dataset, N5Helpers.listAndSortScaleDatasets( n5, dataset )[ 0 ] ).toString();
			LOG.debug( "Got dataset {}", ds );

			final DatasetAttributes dsAttrs = n5.getDatasetAttributes( ds );
			final int nDim = dsAttrs.getNumDimensions();
			setResolution( Optional.ofNullable( n5.getAttribute( ds, RESOLUTION_KEY, double[].class ) ).orElse( DoubleStream.generate( () -> 1.0 ).limit( nDim ).toArray() ) );
			setOffset( Optional.ofNullable( n5.getAttribute( ds, OFFSET_KEY, double[].class ) ).orElse( new double[ nDim ] ) );
			this.datasetInfo.minProperty().set( Optional.ofNullable( n5.getAttribute( ds, MIN_KEY, Double.class ) ).orElse( N5Helpers.minForType( dsAttrs.getDataType() ) ) );
			this.datasetInfo.maxProperty().set( Optional.ofNullable( n5.getAttribute( ds, MAX_KEY, Double.class ) ).orElse( N5Helpers.maxForType( dsAttrs.getDataType() ) ) );
		}
		catch ( final IOException e )
		{
			ExceptionNode.exceptionDialog( e ).show();
		}
	}

	@Override
	public Node getDialogNode()
	{
		return node;
	}

	@Override
	public StringBinding errorMessage()
	{
		return errorMessage;
	}

	@Override
	public DoubleProperty[] resolution()
	{
		return this.datasetInfo.spatialResolutionProperties();
	}

	@Override
	public DoubleProperty[] offset()
	{
		return this.datasetInfo.spatialOffsetProperties();
	}

	@Override
	public DoubleProperty min()
	{
		return this.datasetInfo.minProperty();
	}

	@Override
	public DoubleProperty max()
	{
		return this.datasetInfo.maxProperty();
	}

	@Override
	public FragmentSegmentAssignmentState assignments()
	{
		final String dataset = this.dataset.get() + ".fragment-segment-assignment";

		try
		{
			final N5Writer writer = n5.get();

			final BiConsumer< long[], long[] > persister = ( keys, values ) -> {
				if ( keys.length == 0 )
				{
					LOG.warn( "Zero length data, will not persist fragment-segment-assignment." );
					return;
				}
				try
				{
					final DatasetAttributes attrs = new DatasetAttributes( new long[] { keys.length, 2 }, new int[] { keys.length, 1 }, DataType.UINT64, new GzipCompression() );
					writer.createDataset( dataset, attrs );
					final DataBlock< long[] > keyBlock = new LongArrayDataBlock( new int[] { keys.length, 1 }, new long[] { 0, 0 }, keys );
					final DataBlock< long[] > valueBlock = new LongArrayDataBlock( new int[] { values.length, 1 }, new long[] { 0, 1 }, values );
					writer.writeBlock( dataset, attrs, keyBlock );
					writer.writeBlock( dataset, attrs, valueBlock );
				}
				catch ( final IOException e )
				{
					throw new RuntimeException( e );
				}
			};

			final long[] keys;
			final long[] values;
			LOG.debug( "Found fragment segment assingment dataset {}? {}", dataset, writer.datasetExists( dataset ) );
			if ( writer.datasetExists( dataset ) )
			{
				final DatasetAttributes attrs = writer.getDatasetAttributes( dataset );
				final int numEntries = ( int ) attrs.getDimensions()[ 0 ];
				keys = new long[ numEntries ];
				values = new long[ numEntries ];
				LOG.debug( "Found {} assignments", numEntries );
				final RandomAccessibleInterval< UnsignedLongType > data = N5Utils.open( writer, dataset );

				final Cursor< UnsignedLongType > keysCursor = Views.flatIterable( Views.hyperSlice( data, 1, 0l ) ).cursor();
				for ( int i = 0; keysCursor.hasNext(); ++i )
				{
					keys[ i ] = keysCursor.next().get();
				}

				final Cursor< UnsignedLongType > valuesCursor = Views.flatIterable( Views.hyperSlice( data, 1, 1l ) ).cursor();
				for ( int i = 0; valuesCursor.hasNext(); ++i )
				{
					values[ i ] = valuesCursor.next().get();
				}
			}
			else
			{
				keys = new long[] {};
				values = new long[] {};
			}

			return new FragmentSegmentAssignmentOnlyLocal( keys, values, persister );
		}
		catch ( final IOException e )
		{
			throw new RuntimeException( e );
		}
	}

	@Override
	public IdService idService()
	{
		try
		{
			final N5Writer n5 = this.n5.get();
			final String dataset = this.dataset.get();

			final Long maxId = n5.getAttribute( dataset, "maxId", Long.class );
			final long actualMaxId;
			if ( maxId == null )
			{
				if ( isLabelMultisetType() )
				{
					LOG.debug( "Getting id service for label multisets" );
					actualMaxId = maxIdLabelMultiset( n5, dataset );
				}
				else if ( isIntegerType() )
				{
					actualMaxId = maxId( n5, dataset );
				}
				else
				{
					return null;
				}
			}
			else
			{
				actualMaxId = maxId;
			}
			return new N5IdService( n5, dataset, actualMaxId );

		}
		catch ( final Exception e )
		{
			LOG.warn( "Unable to generate id-service: {}", e );
			e.printStackTrace();
			return null;
		}
	}

	private static < T extends IntegerType< T > & NativeType< T > > long maxId( final N5Reader n5, final String dataset ) throws IOException
	{
		final String ds;
		if ( n5.datasetExists( dataset ) )
		{
			ds = dataset;
		}
		else
		{
			final String[] scaleDirs = N5Helpers.listAndSortScaleDatasets( n5, dataset );
			ds = Paths.get( dataset, scaleDirs ).toString();
		}
		final RandomAccessibleInterval< T > data = N5Utils.open( n5, ds );
		long maxId = 0;
		for ( final T label : Views.flatIterable( data ) )
		{
			maxId = IdService.max( label.getIntegerLong(), maxId );
		}
		return maxId;
	}

	private static long maxIdLabelMultiset( final N5Reader n5, final String dataset ) throws IOException
	{
		final String ds;
		if ( n5.datasetExists( dataset ) )
		{
			ds = dataset;
		}
		else
		{
			final String[] scaleDirs = N5Helpers.listAndSortScaleDatasets( n5, dataset );
			ds = Paths.get( dataset, scaleDirs[ scaleDirs.length - 1 ] ).toString();
		}
		final DatasetAttributes attrs = n5.getDatasetAttributes( ds );
		final N5CacheLoader loader = new N5CacheLoader( n5, ds );
		final BoundedSoftRefLoaderCache< Long, Cell< VolatileLabelMultisetArray > > cache = new BoundedSoftRefLoaderCache<>( 1 );
		final LoaderCacheAsCacheAdapter< Long, Cell< VolatileLabelMultisetArray > > wrappedCache = new LoaderCacheAsCacheAdapter<>( cache, loader );
		final CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > data = new CachedCellImg<>(
				new CellGrid( attrs.getDimensions(), attrs.getBlockSize() ),
				new LabelMultisetType(),
				wrappedCache,
				new VolatileLabelMultisetArray( 0, true ) );
		long maxId = 0;
		for ( final Cell< VolatileLabelMultisetArray > cell : Views.iterable( data.getCells() ) )
		{
			for ( final long id : cell.getData().containedLabels() )
			{
				if ( id > maxId )
				{
					maxId = id;
				}
			}
		}
		return maxId;
	}

	private Node initializeNode(
			final Node rootNode,
			final String datasetPromptText,
			final Consumer< Event > onBrowseClicked )
	{
		final ComboBox< String > datasetDropDown = new ComboBox<>( datasetChoices );
		datasetDropDown.setPromptText( datasetPromptText );
		datasetDropDown.setEditable( false );
		datasetDropDown.valueProperty().bindBidirectional( dataset );
		datasetDropDown.disableProperty().bind( this.isN5Valid.not() );
		final GridPane grid = new GridPane();
		grid.add( rootNode, 0, 0 );
		grid.add( datasetDropDown, 0, 1 );
		GridPane.setHgrow( rootNode, Priority.ALWAYS );
		GridPane.setHgrow( datasetDropDown, Priority.ALWAYS );
		final Button button = new Button( "Browse" );
		button.setOnAction( onBrowseClicked::accept );
		grid.add( button, 1, 0 );

		return grid;
	}

	@Override
	public ObservableStringValue nameProperty()
	{
		return name;
	}

	@Override
	public String identifier()
	{
		return identifier;
	}

	@Override
	public < T extends NativeType< T >, V extends Volatile< T > > Triple< RandomAccessibleInterval< T >[], RandomAccessibleInterval< V >[], AffineTransform3D[] > getDataAndVolatile( final SharedQueue sharedQueue, final int priority ) throws IOException
	{

		final boolean isLabelMultisetType = MakeUnchecked.unchecked( this::isLabelMultisetType ).get();
		LOG.debug( "Source is label multiset? {}", isLabelMultisetType );
		if ( isLabelMultisetType )
		{
			final N5Reader reader = this.n5.get();
			final String dataset = this.dataset.get();
			try
			{
				if ( reader.datasetExists( dataset ) )
				{
					final DatasetAttributes attrs = reader.getDatasetAttributes( dataset );
					final N5CacheLoader loader = new N5CacheLoader( reader, dataset );
					final SoftRefLoaderCache< Long, Cell< VolatileLabelMultisetArray > > cache = new SoftRefLoaderCache<>();
					final LoaderCacheAsCacheAdapter< Long, Cell< VolatileLabelMultisetArray > > wrappedCache = new LoaderCacheAsCacheAdapter<>( cache, loader );
					final CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > cachedImg = new CachedCellImg<>(
							new CellGrid( attrs.getDimensions(), attrs.getBlockSize() ),
							new LabelMultisetType(),
							wrappedCache,
							new VolatileLabelMultisetArray( 0, true ) );
					final RandomAccessibleInterval< T > raw = ( RandomAccessibleInterval< T > ) cachedImg;
//							N5Utils.openVolatile( reader, dataset );
					final VolatileRandomAccessibleIntervalView< LabelMultisetType, VolatileLabelMultisetType > volatileCachedImg = VolatileHelpers.wrapCachedCellImg(
							cachedImg,
							new VolatileHelpers.CreateInvalidVolatileLabelMultisetArray( cachedImg.getCellGrid() ),
							sharedQueue,
							new CacheHints( LoadingStrategy.VOLATILE, priority, false ),
							new VolatileLabelMultisetType() );
					final RandomAccessibleInterval< V > vraw = ( RandomAccessibleInterval< V > ) volatileCachedImg;
//							VolatileViews.wrapAsVolatile( raw, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) );
					final double[] resolution = Arrays.stream( resolution() ).mapToDouble( DoubleProperty::get ).toArray();
					final double[] offset = Arrays.stream( offset() ).mapToDouble( DoubleProperty::get ).toArray();
					final AffineTransform3D transform = new AffineTransform3D();
					transform.set(
							resolution[ 0 ], 0, 0, offset[ 0 ],
							0, resolution[ 1 ], 0, offset[ 1 ],
							0, 0, resolution[ 2 ], offset[ 2 ] );
					LOG.debug( "Resolution={}", Arrays.toString( resolution ) );
					return new ValueTriple<>( new RandomAccessibleInterval[] { raw }, new RandomAccessibleInterval[] { vraw }, new AffineTransform3D[] { transform } );
				}

			}
			catch ( final IOException e )
			{

			}

			// do multiscale instead
			final String[] scaleDatasets = N5Helpers.listAndSortScaleDatasets( reader, dataset );

			LOG.debug( "Opening directories {} as multi-scale in {}: ", Arrays.toString( scaleDatasets ), dataset );

			final RandomAccessibleInterval< T >[] raw = new RandomAccessibleInterval[ scaleDatasets.length ];
			final RandomAccessibleInterval< V >[] vraw = new RandomAccessibleInterval[ scaleDatasets.length ];
			final AffineTransform3D[] transforms = new AffineTransform3D[ scaleDatasets.length ];
			final double[] initialResolution = Arrays.stream( resolution() ).mapToDouble( DoubleProperty::get ).toArray();
			final double[] initialDonwsamplingFactors = Optional
					.ofNullable( reader.getAttribute( dataset + "/" + scaleDatasets[ 0 ], "downsamplingFactors", double[].class ) )
					.orElse( new double[] { 1, 1, 1 } );
			final double[] offset = Arrays.stream( offset() ).mapToDouble( DoubleProperty::get ).toArray();
			LOG.debug( "Initial resolution={}", Arrays.toString( initialResolution ) );
			final ExecutorService exec = Executors.newFixedThreadPool( scaleDatasets.length );
			final ArrayList< Future< Boolean > > futures = new ArrayList<>();
			for ( int scale = 0; scale < scaleDatasets.length; ++scale )
			{
				final int fScale = scale;
				futures.add( exec.submit( () -> {
					LOG.debug( "Populating scale level {}", fScale );
					try
					{
						final String scaleDataset = dataset + "/" + scaleDatasets[ fScale ];

						final DatasetAttributes attrs = reader.getDatasetAttributes( scaleDataset );
						final N5CacheLoader loader = new N5CacheLoader( reader, scaleDataset );
						final SoftRefLoaderCache< Long, Cell< VolatileLabelMultisetArray > > cache = new SoftRefLoaderCache<>();
						final LoaderCacheAsCacheAdapter< Long, Cell< VolatileLabelMultisetArray > > wrappedCache = new LoaderCacheAsCacheAdapter<>( cache, loader );
						final CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > cachedImg = new CachedCellImg<>(
								new CellGrid( attrs.getDimensions(), attrs.getBlockSize() ),
								new LabelMultisetType(),
								wrappedCache,
								new VolatileLabelMultisetArray( 0, true ) );
						raw[ fScale ] = ( RandomAccessibleInterval< T > ) cachedImg;
						// TODO cannot use VolatileViews because VolatileTypeMatches
						// does not know LabelMultisetType
		//				vraw[ scale ] = VolatileViews.wrapAsVolatile( raw[ scale ], sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) );
						final VolatileRandomAccessibleIntervalView< LabelMultisetType, VolatileLabelMultisetType > volatileCachedImg = VolatileHelpers.wrapCachedCellImg(
								cachedImg,
								new VolatileHelpers.CreateInvalidVolatileLabelMultisetArray( cachedImg.getCellGrid() ),
								sharedQueue,
								new CacheHints( LoadingStrategy.VOLATILE, priority, false ),
								new VolatileLabelMultisetType() );
						vraw[ fScale ] = ( RandomAccessibleInterval< V > ) volatileCachedImg;

						final double[] downsamplingFactors = Optional
								.ofNullable( reader.getAttribute( scaleDataset, "downsamplingFactors", double[].class ) )
								.orElse( new double[] { 1, 1, 1 } );
						LOG.debug( "Read downsampling factors: {}", Arrays.toString( downsamplingFactors ) );

						final double[] scaledResolution = new double[ downsamplingFactors.length ];
						final double[] shift = new double[ downsamplingFactors.length ];

						for ( int d = 0; d < downsamplingFactors.length; ++d )
						{
							scaledResolution[ d ] = downsamplingFactors[ d ] * initialResolution[ d ];
							shift[ d ] = 0.5 / initialDonwsamplingFactors[ d ] - 0.5 / downsamplingFactors[ d ];
						}

						LOG.debug( "Downsampling factors={}, scaled resolution={}", Arrays.toString( downsamplingFactors ), Arrays.toString( scaledResolution ) );

						final AffineTransform3D transform = new AffineTransform3D();
						transform.set(
								scaledResolution[ 0 ], 0, 0, offset[ 0 ],
								0, scaledResolution[ 1 ], 0, offset[ 1 ],
								0, 0, scaledResolution[ 2 ], offset[ 2 ] );
						transforms[ fScale ] = transform.concatenate( new Translation3D( shift ) );
						LOG.debug( "Populated scale level {}", fScale );
						return true;
					}
					catch ( final IOException e )
					{
						LOG.debug( e.toString(), e );
						return false;
					}
				} ) );
			}
			for ( final Future< Boolean > future : futures )
				try
				{
					if ( !future.get() )
						throw new IOException( "Failed populating " );
				}
				catch ( final ExecutionException | InterruptedException e )
				{
					throw new IOException( "Failed populating " );
				}
			exec.shutdown();
			return new ValueTriple<>( raw, vraw, transforms );
		}
		else
		{
			final N5Reader reader = this.n5.get();
			final String dataset = this.dataset.get();
			try
			{
				if ( reader.datasetExists( dataset ) )
				{
					final RandomAccessibleInterval< T > raw = N5Utils.openVolatile( reader, dataset );
					final RandomAccessibleInterval< V > vraw = VolatileViews.wrapAsVolatile( raw, sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) );
					final double[] resolution = Arrays.stream( resolution() ).mapToDouble( DoubleProperty::get ).toArray();
					final double[] offset = Arrays.stream( offset() ).mapToDouble( DoubleProperty::get ).toArray();
					final AffineTransform3D transform = new AffineTransform3D();
					transform.set(
							resolution[ 0 ], 0, 0, offset[ 0 ],
							0, resolution[ 1 ], 0, offset[ 1 ],
							0, 0, resolution[ 2 ], offset[ 2 ] );
					LOG.debug( "Resolution={}", Arrays.toString( resolution ) );
					return new ValueTriple<>( new RandomAccessibleInterval[] { raw }, new RandomAccessibleInterval[] { vraw }, new AffineTransform3D[] { transform } );
				}
			}
			catch ( final IOException e )
			{

			}
			final String[] scaleDatasets = N5Helpers.listAndSortScaleDatasets( reader, dataset );

			LOG.debug( "Opening directories {} as multi-scale in {}: ", Arrays.toString( scaleDatasets ), dataset );

			final RandomAccessibleInterval< T >[] raw = new RandomAccessibleInterval[ scaleDatasets.length ];
			final RandomAccessibleInterval< V >[] vraw = new RandomAccessibleInterval[ scaleDatasets.length ];
			final AffineTransform3D[] transforms = new AffineTransform3D[ scaleDatasets.length ];
			final double[] initialResolution = Arrays.stream( resolution() ).mapToDouble( DoubleProperty::get ).toArray();
			final double[] initialDonwsamplingFactors = Optional.ofNullable( reader.getAttribute( dataset + "/" + scaleDatasets[ 0 ], "downsamplingFactors", double[].class ) ).orElse( new double[] { 1, 1, 1 } );
			final double[] offset = Arrays.stream( offset() ).mapToDouble( DoubleProperty::get ).toArray();
			LOG.debug( "Initial resolution={}", Arrays.toString( initialResolution ) );
			final ExecutorService exec = Executors.newFixedThreadPool( scaleDatasets.length );
			final ArrayList< Future< Boolean > > futures = new ArrayList<>();
			for ( int scale = 0; scale < scaleDatasets.length; ++scale )
			{
				final int fScale = scale;
				futures.add( exec.submit( () -> {
					try
					{
						LOG.debug( "Populating scale level {}", fScale );
						final String scaleDataset = dataset + "/" + scaleDatasets[ fScale ];
						raw[ fScale ] = N5Utils.openVolatile( reader, scaleDataset );
						vraw[ fScale ] = VolatileViews.wrapAsVolatile( raw[ fScale ], sharedQueue, new CacheHints( LoadingStrategy.VOLATILE, priority, true ) );

						final double[] downsamplingFactors = Optional.ofNullable( reader.getAttribute( scaleDataset, "downsamplingFactors", double[].class ) ).orElse( new double[] { 1, 1, 1 } );

						final double[] scaledResolution = new double[ downsamplingFactors.length ];
						final double[] shift = new double[ downsamplingFactors.length ];

						for ( int d = 0; d < downsamplingFactors.length; ++d )
						{
							scaledResolution[ d ] = downsamplingFactors[ d ] * initialResolution[ d ];
							shift[ d ] = 0.5 / initialDonwsamplingFactors[ d ] - 0.5 / downsamplingFactors[ d ];
						}

						LOG.debug( "Downsampling factors={}, scaled resolution={}", Arrays.toString( downsamplingFactors ), Arrays.toString( scaledResolution ) );

						final AffineTransform3D transform = new AffineTransform3D();
						transform.set(
								scaledResolution[ 0 ], 0, 0, offset[ 0 ],
								0, scaledResolution[ 1 ], 0, offset[ 1 ],
								0, 0, scaledResolution[ 2 ], offset[ 2 ] );
						transforms[ fScale ] = transform.concatenate( new Translation3D( shift ) );
						LOG.debug( "Populated scale level {}", fScale );
						return true;
					}
					catch ( final IOException e )
					{
						LOG.debug( e.toString(), e );
						return false;
					}
				} ) );
			}
			for ( final Future< Boolean > future : futures )
				try
				{
					if ( !future.get() )
						throw new IOException( "Failed populating " );
				}
				catch ( final ExecutionException | InterruptedException e )
				{
					throw new IOException( "Failed populating " );
				}
			exec.shutdown();
			return new ValueTriple<>( raw, vraw, transforms );
		}
	}

	@Override
	public boolean isLabelType() throws Exception
	{
		return isIntegerType() || isLabelMultisetType();
	}

	@Override
	public boolean isLabelMultisetType() throws Exception
	{
		final Boolean attribute = getAttribute( LABEL_MULTISETTYPE_KEY, Boolean.class );
		LOG.debug( "Getting label multiset attribute: {}", attribute );
		return Optional.ofNullable( attribute ).orElse( false );
	}

	public DatasetAttributes getAttributes() throws IOException
	{
		final N5Reader n5 = this.n5.get();
		final String ds = this.dataset.get();

		if ( n5.datasetExists( ds ) )
		{
			LOG.debug( "Getting attributes for {} and {}", n5, ds );
			return n5.getDatasetAttributes( ds );
		}

		final String[] scaleDirs = N5Helpers.listAndSortScaleDatasets( n5, ds );

		if ( scaleDirs.length > 0 )
		{
			LOG.debug( "Getting attributes for {} and {}", n5, scaleDirs[ 0 ] );
			return n5.getDatasetAttributes( Paths.get( ds, scaleDirs[ 0 ] ).toString() );
		}

		throw new RuntimeException( String.format( "Cannot read dataset attributes for group %s and dataset %s.", n5, ds ) );

	}

	public DataType getDataType() throws IOException
	{
		return getAttributes().getDataType();
	}

	@Override
	public boolean isIntegerType() throws Exception
	{
		return N5Helpers.isIntegerType( getDataType() );
	}

	@Override
	public BiConsumer< CachedCellImg< UnsignedLongType, ? >, long[] > commitCanvas()
	{
		final String dataset = this.dataset.get();
		final N5Writer writer = this.n5.get();
		return ( canvas, blocks ) -> {
			try
			{
				final N5Writer n5 = writer;

				final boolean isMultiscale = !n5.datasetExists( dataset );

				final CellGrid canvasGrid = canvas.getCellGrid();

				final String highestResolutionDataset = isMultiscale ? Paths.get( dataset, N5Helpers.listAndSortScaleDatasets( n5, dataset )[ 0 ] ).toString() : dataset;

				if ( !Optional.ofNullable( n5.getAttribute( highestResolutionDataset, LABEL_MULTISETTYPE_KEY, Boolean.class ) ).orElse( false ) ) { throw new RuntimeException( "Only label multiset type accepted currently!" ); }

				final DatasetAttributes highestResolutionAttributes = n5.getDatasetAttributes( highestResolutionDataset );
				final CellGrid highestResolutionGrid = new CellGrid( highestResolutionAttributes.getDimensions(), highestResolutionAttributes.getBlockSize() );

				if ( !highestResolutionGrid.equals( canvasGrid ) )
				{
					LOG.error( "Canvas grid {} and highest resolution dataset grid {} incompatible!", canvasGrid, highestResolutionGrid );
					throw new RuntimeException( String.format( "Canvas grid %s and highest resolution dataset grid %s incompatible!", canvasGrid, highestResolutionGrid ) );
				}

				final int[] highestResolutionBlockSize = highestResolutionAttributes.getBlockSize();
				final long[] highestResolutionDimensions = highestResolutionAttributes.getDimensions();

				final long[] gridPosition = new long[ highestResolutionBlockSize.length ];
				final long[] min = new long[ highestResolutionBlockSize.length ];
				final long[] max = new long[ highestResolutionBlockSize.length ];

				final RandomAccessibleInterval< LabelMultisetType > highestResolutionData = LabelUtils.openVolatile( n5, highestResolutionDataset );

				LOG.debug( "Persisting canvas with grid={} into background with grid={}", canvasGrid, highestResolutionGrid );

				for ( final long blockId : blocks )
				{
					highestResolutionGrid.getCellGridPositionFlat( blockId, gridPosition );
					Arrays.setAll( min, d -> gridPosition[ d ] * highestResolutionBlockSize[ d ] );
					Arrays.setAll( max, d -> Math.min( min[ d ] + highestResolutionBlockSize[ d ], highestResolutionDimensions[ d ] ) - 1 );

					final RandomAccessibleInterval< LabelMultisetType > convertedToMultisets = Converters.convert(
							( RandomAccessibleInterval< UnsignedLongType > ) canvas,
							new FromIntegerTypeConverter<>(),
							FromIntegerTypeConverter.geAppropriateType() );

					final IntervalView< Pair< LabelMultisetType, LabelMultisetType > > blockWithBackground =
							Views.interval( Views.pair( convertedToMultisets, highestResolutionData ), min, max );

					final int numElements = ( int ) Intervals.numElements( blockWithBackground );

					final Iterable< LabelMultisetType > pairIterable = () -> new Iterator< LabelMultisetType >()
					{

						Iterator< Pair< LabelMultisetType, LabelMultisetType > > iterator = Views.flatIterable( blockWithBackground ).iterator();

						@Override
						public boolean hasNext()
						{
							return iterator.hasNext();
						}

						@Override
						public LabelMultisetType next()
						{
							final Pair< LabelMultisetType, LabelMultisetType > p = iterator.next();
							final LabelMultisetType a = p.getA();
							if ( a.entrySet().iterator().next().getElement().id() == Label.INVALID )
							{
								return p.getB();
							}
							else
							{
								return a;
							}
						}

					};

					final byte[] byteData = LabelUtils.serializeLabelMultisetTypes( pairIterable, numElements );
					final ByteArrayDataBlock dataBlock = new ByteArrayDataBlock( Intervals.dimensionsAsIntArray( blockWithBackground ), gridPosition, byteData );
					n5.writeBlock( highestResolutionDataset, highestResolutionAttributes, dataBlock );

				}

				if ( isMultiscale )
				{
					final String[] scaleDatasets = N5Helpers.listAndSortScaleDatasets( n5, dataset );
					for ( int level = 1; level < scaleDatasets.length; ++level )
					{
						final String targetDataset = Paths.get( dataset, scaleDatasets[ level ] ).toString();
						final String previousDataset = Paths.get( dataset, scaleDatasets[ level - 1 ] ).toString();

						final DatasetAttributes targetAttributes = n5.getDatasetAttributes( targetDataset );
						final DatasetAttributes previousAttributes = n5.getDatasetAttributes( previousDataset );

						final double[] targetDownsamplingFactors = n5.getAttribute( targetDataset, DOWNSAMPLING_FACTORS_KEY, double[].class );
						final double[] previousDownsamplingFactors = Optional.ofNullable( n5.getAttribute( previousDataset, DOWNSAMPLING_FACTORS_KEY, double[].class ) ).orElse( new double[] { 1, 1, 1 } );
						final double[] relativeDownsamplingFactors = new double[ targetDownsamplingFactors.length ];
						Arrays.setAll( relativeDownsamplingFactors, d -> targetDownsamplingFactors[ d ] / previousDownsamplingFactors[ d ] );

						final CellGrid targetGrid = new CellGrid( targetAttributes.getDimensions(), targetAttributes.getBlockSize() );
						final CellGrid previousGrid = new CellGrid( previousAttributes.getDimensions(), previousAttributes.getBlockSize() );

						final long[] affectedBlocks = MaskedSource.scaleBlocksToHigherLevel( blocks, highestResolutionGrid, targetGrid, targetDownsamplingFactors ).toArray();

						final CachedCellImg< LabelMultisetType, VolatileLabelMultisetArray > previousData = LabelUtils.openVolatile( n5, previousDataset );
						final RandomAccess< Cell< VolatileLabelMultisetArray > > previousCellsAccess = previousData.getCells().randomAccess();

						final long[] blockPosition = new long[ targetGrid.numDimensions() ];
						final double[] blockMinDouble = new double[ blockPosition.length ];
						final double[] blockMaxDouble = new double[ blockPosition.length ];

						final long[] blockMin = new long[ blockMinDouble.length ];
						final long[] blockMax = new long[ blockMinDouble.length ];

						final int[] targetBlockSize = targetAttributes.getBlockSize();
						final int[] previousBlockSize = previousAttributes.getBlockSize();

						final long[] targetDimensions = targetAttributes.getDimensions();
						final long[] previousDimensions = previousAttributes.getDimensions();

						final Scale3D targetToPrevious = new Scale3D( relativeDownsamplingFactors );

						final TLongHashSet relevantBlocksAtPrevious = new TLongHashSet();

						final int targetMaxNumEntries = Optional.ofNullable( n5.getAttribute( targetDataset, MAX_NUM_ENTRIES_KEY, Integer.class ) ).orElse( -1 );

						final int[] relativeFactors = DoubleStream.of( relativeDownsamplingFactors ).mapToInt( d -> ( int ) d ).toArray();

						final int[] ones = { 1, 1, 1 };

						final long[] previousRelevantIntervalMin = new long[ blockMin.length ];
						final long[] previousRelevantIntervalMax = new long[ blockMin.length ];

						LOG.debug( "level={}: Got {} blocks", level, affectedBlocks.length );

						for ( final long targetBlock : affectedBlocks )
						{
							targetGrid.getCellGridPositionFlat( targetBlock, blockPosition );

							blockMinDouble[ 0 ] = blockPosition[ 0 ] * targetBlockSize[ 0 ];
							blockMinDouble[ 1 ] = blockPosition[ 1 ] * targetBlockSize[ 1 ];
							blockMinDouble[ 2 ] = blockPosition[ 2 ] * targetBlockSize[ 2 ];

							blockMaxDouble[ 0 ] = blockMinDouble[ 0 ] + targetBlockSize[ 0 ];
							blockMaxDouble[ 1 ] = blockMinDouble[ 1 ] + targetBlockSize[ 1 ];
							blockMaxDouble[ 2 ] = blockMinDouble[ 2 ] + targetBlockSize[ 2 ];

							LOG.debug( "level={}: Downsampling block {} with min={} max={} in tarspace.", level, blockPosition, blockMinDouble, blockMaxDouble );

							final int[] size = {
									( int ) ( Math.min( blockMaxDouble[ 0 ], targetDimensions[ 0 ] ) - blockMinDouble[ 0 ] ),
									( int ) ( Math.min( blockMaxDouble[ 1 ], targetDimensions[ 1 ] ) - blockMinDouble[ 1 ] ),
									( int ) ( Math.min( blockMaxDouble[ 2 ], targetDimensions[ 2 ] ) - blockMinDouble[ 2 ] ) };

							targetToPrevious.apply( blockMinDouble, blockMinDouble );
							targetToPrevious.apply( blockMaxDouble, blockMaxDouble );

							blockMin[ 0 ] = Math.min( ( long ) blockMinDouble[ 0 ], previousDimensions[ 0 ] );
							blockMin[ 1 ] = Math.min( ( long ) blockMinDouble[ 1 ], previousDimensions[ 0 ] );
							blockMin[ 2 ] = Math.min( ( long ) blockMinDouble[ 2 ], previousDimensions[ 0 ] );

							blockMax[ 0 ] = Math.min( ( long ) blockMaxDouble[ 0 ], previousDimensions[ 0 ] );
							blockMax[ 1 ] = Math.min( ( long ) blockMaxDouble[ 1 ], previousDimensions[ 1 ] );
							blockMax[ 2 ] = Math.min( ( long ) blockMaxDouble[ 2 ], previousDimensions[ 2 ] );

							previousRelevantIntervalMin[ 0 ] = blockMin[ 0 ];
							previousRelevantIntervalMin[ 1 ] = blockMin[ 1 ];
							previousRelevantIntervalMin[ 2 ] = blockMin[ 2 ];

							previousRelevantIntervalMax[ 0 ] = blockMax[ 0 ] - 1;
							previousRelevantIntervalMax[ 1 ] = blockMax[ 1 ] - 1;
							previousRelevantIntervalMax[ 2 ] = blockMax[ 2 ] - 1;

							blockMin[ 0 ] /= previousBlockSize[ 0 ];
							blockMin[ 1 ] /= previousBlockSize[ 1 ];
							blockMin[ 2 ] /= previousBlockSize[ 2 ];

							blockMax[ 0 ] = Math.max( blockMax[ 0 ] / previousBlockSize[ 0 ] - 1, blockMin[ 0 ] );
							blockMax[ 1 ] = Math.max( blockMax[ 1 ] / previousBlockSize[ 1 ] - 1, blockMin[ 1 ] );
							blockMax[ 2 ] = Math.max( blockMax[ 2 ] / previousBlockSize[ 2 ] - 1, blockMin[ 2 ] );

							LOG.debug( "level={}: Downsampling contained label lists for block {} with min={} max={} in previous space.", level, blockPosition, blockMin, blockMax );

							relevantBlocksAtPrevious.clear();
							Grids.forEachOffset( blockMin, blockMax, ones, offset -> {
								previousCellsAccess.setPosition( offset[ 0 ], 0 );
								previousCellsAccess.setPosition( offset[ 1 ], 1 );
								previousCellsAccess.setPosition( offset[ 2 ], 2 );
								relevantBlocksAtPrevious.addAll( previousCellsAccess.get().getData().containedLabels() );
							} );

							LOG.debug( "level={}: Creating downscaled for interval=({} {})", level, previousRelevantIntervalMin, previousRelevantIntervalMax );

							final VolatileLabelMultisetArray updatedAccess = LabelMultisetTypeDownscaler.createDownscaledCell(
									Views.zeroMin( Views.interval( previousData, previousRelevantIntervalMin, previousRelevantIntervalMax ) ),
									relativeFactors,
									relevantBlocksAtPrevious,
									targetMaxNumEntries );

							final byte[] serializedAccess = new byte[ LabelMultisetTypeDownscaler.getSerializedVolatileLabelMultisetArraySize( updatedAccess ) ];
							LabelMultisetTypeDownscaler.serializeVolatileLabelMultisetArray( updatedAccess, serializedAccess );

							LOG.debug( "level={}: Writing block of size {} at {}.", level, size, blockPosition );

							n5.writeBlock( targetDataset, targetAttributes, new ByteArrayDataBlock( size, blockPosition, serializedAccess ) );

						}

					}

//					throw new RuntimeException( "multi-scale export not implemented yet!" );
				}

//				if ( isIntegerType() )
//					commitForIntegerType( n5, dataset, canvas );
			}
			catch ( final IOException e )
			{
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		};
	}

	public < T > T getAttribute( final String key, final Class< T > clazz ) throws IOException
	{
		final N5Reader n5 = this.n5.get();
		final String ds = this.dataset.get();

		if ( n5.datasetExists( ds ) )
		{
			LOG.debug( "Getting attributes for {} and {}", n5, ds );
			return n5.getAttribute( ds, key, clazz );
		}

		final String[] scaleDirs = N5Helpers.listAndSortScaleDatasets( n5, ds );

		if ( scaleDirs.length > 0 )
		{
			LOG.warn( "Getting attributes for mipmap dataset: {} and {}", n5, scaleDirs[ 0 ] );
			return n5.getAttribute( Paths.get( ds, scaleDirs[ 0 ] ).toString(), key, clazz );
		}

		throw new RuntimeException( String.format( "Cannot read dataset attributes for group %s and dataset %s.", n5, ds ) );
	}

}
