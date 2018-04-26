package org.janelia.saalfeldlab.paintera.ui.opendialog;

import org.janelia.saalfeldlab.paintera.data.DataSource;

import bdv.util.volatiles.SharedQueue;
import javafx.beans.property.DoubleProperty;
import javafx.beans.value.ObservableStringValue;
import javafx.beans.value.ObservableValue;
import javafx.scene.Node;
import net.imglib2.Volatile;
import net.imglib2.type.NativeType;
import net.imglib2.type.Type;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.volatiles.AbstractVolatileRealType;

public interface BackendDialog
{

	public Node getDialogNode();

	public ObservableValue< String > errorMessage();

	public < T extends RealType< T > & NativeType< T >, V extends AbstractVolatileRealType< T, V > & NativeType< V > > DataSource< T, V > getRaw(
			final String name,
			final SharedQueue sharedQueue,
			final int priority ) throws Exception;

	public < D extends NativeType< D >, T extends Volatile< D > & Type< T > > LabelDataSourceRepresentation< D, T > getLabels(
			final String name,
			final SharedQueue sharedQueue,
			final int priority ) throws Exception;

	public DoubleProperty[] resolution();

	public default void setResolution( final double[] resolution )
	{
		final DoubleProperty[] res = resolution();
		for ( int i = 0; i < res.length; ++i )
		{
			res[ i ].set( resolution[ i ] );
		}
	}

	public DoubleProperty[] offset();

	public default void setOffset( final double[] offset )
	{
		final DoubleProperty[] off = offset();
		for ( int i = 0; i < off.length; ++i )
		{
			off[ i ].set( offset[ i ] );
		}
	}

	public DoubleProperty min();

	public DoubleProperty max();

	public ObservableStringValue nameProperty();

	public String identifier();

}