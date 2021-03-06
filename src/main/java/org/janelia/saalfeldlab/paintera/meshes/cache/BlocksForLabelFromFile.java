package org.janelia.saalfeldlab.paintera.meshes.cache;

import java.lang.invoke.MethodHandles;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import com.google.gson.annotations.Expose;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import org.janelia.saalfeldlab.paintera.meshes.InterruptibleFunction;
import org.janelia.saalfeldlab.util.HashWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BlocksForLabelFromFile implements InterruptibleFunction<Long, Interval[]>
{

	private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

	private static final Interval[] EMPTY_ARRAY = {};

	// 3 : nDim
	// 2 : min and max
	private static final int SINGLE_ENTRY_BYTE_SIZE = 3 * 2 * Long.BYTES;

	@Expose
	private final String pattern;

	public BlocksForLabelFromFile(final String pattern)
	{
		super();
		this.pattern = pattern;
	}

	@Override
	public Interval[] apply(final Long t)
	{
		if (pattern == null)
		{
			LOG.warn("Invalid pattern, returning empty array: {}", pattern);
			return EMPTY_ARRAY;
		}

		final String path = String.format(pattern, t);
		try
		{
			final byte[] bytes = Files.readAllBytes(Paths.get(path));
			if (!isValidByteSize(bytes.length)) { throw new InvalidFileSize(bytes.length); }

			final Set<HashWrapper<Interval>> intervals = new HashSet<>();

			final ByteBuffer bb = ByteBuffer.wrap(bytes);

			while (bb.hasRemaining())
			{
				intervals.add(HashWrapper.interval(new FinalInterval(
						new long[] {bb.getLong(), bb.getLong(), bb.getLong()},
						new long[] {bb.getLong(), bb.getLong(), bb.getLong()}
				)));
			}

			return intervals
					.stream()
					.map(HashWrapper::getData)
					.toArray(Interval[]::new);

		} catch (final Exception e)
		{
			LOG.error(
					"Unable to read data from file at {} generated by pattern {} and id {} -- returning empty array: " +
							"{}",
					path, pattern, t,
					e.getMessage()
			         );
			return EMPTY_ARRAY;
		}
	}

	private static boolean isValidByteSize(final int sizeInBytes)
	{
		return sizeInBytes % SINGLE_ENTRY_BYTE_SIZE == 0;
	}

	private static class InvalidFileSize extends Exception
	{

		/**
		 *
		 */
		private static final long serialVersionUID = 3063871520312958385L;

		public InvalidFileSize(final int sizeInBytes)
		{
			super("Expected file size in bytes of integer multiple of " + SINGLE_ENTRY_BYTE_SIZE + " but got " +
					sizeInBytes);
		}

	}

	@Override
	public void interruptFor(final Long t)
	{
		// nothing to do here
	}

}
