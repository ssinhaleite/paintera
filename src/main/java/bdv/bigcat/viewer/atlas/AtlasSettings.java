package bdv.bigcat.viewer.atlas;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.value.ObservableBooleanValue;

public class AtlasSettings
{

	private final BooleanProperty changed = new SimpleBooleanProperty();
	{
		changed.addListener( ( obs, oldv, newv ) -> changed.set( false ) );
	}

	private final BooleanProperty allowRotations = new SimpleBooleanProperty( true );
	{
		allowRotations.addListener( ( obs, oldv, newv ) -> changed.set( true ) );
	}

	private final DoubleProperty zoomSpeed = new SimpleDoubleProperty( 1.05 );
	{
		zoomSpeed.addListener( c -> changed.set( true ) );
	}

	private final DoubleProperty rotationSpeed = new SimpleDoubleProperty( 1 );
	{
		rotationSpeed.addListener( c -> changed.set( true ) );
	}

	private final DoubleProperty translationSpeed = new SimpleDoubleProperty( 1 );
	{
		translationSpeed.addListener( c -> changed.set( true ) );
	}

	private final IntegerProperty meshSimplificationIterations = new SimpleIntegerProperty( 0 );
	{
		meshSimplificationIterations.addListener( ( obs, oldv, newv ) -> meshSimplificationIterations.set( Math.max( newv.intValue(), 0 ) ) );
		meshSimplificationIterations.addListener( ( obs, oldv, newv ) -> System.out.println( "CHANGING! " + oldv + " " + newv ) );
	}

	public ObservableBooleanValue changedProperty()
	{
		return ReadOnlyBooleanProperty.readOnlyBooleanProperty( changed );
	}

	public BooleanProperty allowRotationsProperty()
	{
		return allowRotations;
	}

	public DoubleProperty zoomSpeedProperty()
	{
		return this.zoomSpeed;
	}

	public DoubleProperty rotationSpeedProperty()
	{
		return this.rotationSpeed;
	}

	public DoubleProperty translationSpeedProperty()
	{
		return this.translationSpeed;
	}

	public IntegerProperty meshSimplificationIterationsProperty()
	{
		return this.meshSimplificationIterations;
	}

}
