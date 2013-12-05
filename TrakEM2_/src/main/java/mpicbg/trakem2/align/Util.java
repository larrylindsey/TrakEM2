/**
 * License: GPL
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License 2
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */
package mpicbg.trakem2.align;

import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.Patch;
import ini.trakem2.persistence.FSLoader;
import ini.trakem2.persistence.Loader;
import ini.trakem2.utils.Utils;

import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;

import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractModel;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.TranslationModel2D;
import mpicbg.trakem2.transform.AffineModel2D;
import mpicbg.trakem2.transform.CoordinateTransform;
import mpicbg.trakem2.transform.CoordinateTransformList;

/**
 * 
 *
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 * @version 0.1a
 */
public class Util
{
	final static protected class Features implements Serializable
	{
		private static final long serialVersionUID = -5707602842402593215L;
		
		final Object key;
		final ArrayList< Feature > features;
		Features( final Object key, final ArrayList< Feature > features )
		{
			this.key = key;
			this.features = features;
		}
	}
	
	final static protected class PointMatches implements Serializable
	{
		private static final long serialVersionUID = 7905488521562090982L;
		
		final Object key;
		final ArrayList< PointMatch > pointMatches;
		PointMatches( final Object key, final ArrayList< PointMatch > pointMatches )
		{
			this.key = key;
			this.pointMatches = pointMatches;
		}
	}
	
	
	/**
	 * Save a {@link Collection} of {@link Feature Features} to the TrakEM2
	 * project folder.  The saved file contains a key {@link Object} which
	 * may specify the properties of the {@link Feature} {@link Collection}. 
	 *  
	 * @param project
	 * @param key
	 * @param prefix
	 * @param id
	 * @param f
	 * @return
	 */
	final static public boolean serializeFeatures(
			final Project project,
			final Object key,
			final String prefix,
			final long id,
			final Collection< Feature > f )
	{
		final ArrayList< Feature > list = new ArrayList< Feature >();
		list.addAll( f );
		
		final String name = prefix == null ? "features" : prefix + ".features";
		
		final Loader loader = project.getLoader();
		final Features fe = new Features( key, list );
        final String path = new StringBuilder( loader.getUNUIdFolder() )
                .append( "features.ser/" )
                .append( FSLoader.createIdPath( Long.toString( id ), name, ".ser" ) ).toString();
        if (fe.features != null)
        {
            System.out.println("Serializing " + fe.features.size() + " features to " + path + " ...");
        }
        else
        {
            System.out.println("Serializing NULL features to " + path + " ...");
        }
        final boolean ok = loader.serialize(fe, path );
        System.out.println("\t" + (ok ? "ok" : "FAIL"));
        return ok;
	}

	
	/**
	 * Retrieve a {@link Collection} of {@link Feature Features} from the
	 * TrakEM2 project folder.  The {@link Collection} is only returned if
	 * <ol>
	 * <li>the file as identified by project, prefix, and id exists and</li>
	 * <li>its contained key {@link Object#equals(Object) equals} key.</li>
	 * </ol>
	 * 
	 * @param project
	 * @param key
	 * @param prefix
	 * @param id
	 * @return
	 */
	final static protected ArrayList< Feature > deserializeFeatures(
			final Project project,
			final Object key,
			final String prefix,
			final long id )
	{
		final String name = prefix == null ? "features" : prefix + ".features";
		
		final Loader loader = project.getLoader();
        final String path =
                new StringBuilder( loader.getUNUIdFolder() )
                        .append( "features.ser/" )
                        .append( FSLoader.createIdPath( Long.toString( id ), name, ".ser" ) ).toString();

        System.out.println("Deserializing features from " + path);

		final Object ob = loader.deserialize( path );
		
		if ( ob != null )
		{
            System.out.println("\tDeserialized object was not null");
			try
			{
				final Features fe = ( Features )ob;
//				Utils.log( fe.key == null ? "key is null" : key.equals( fe.key ) ? "key is equal" : "key is not equal" );
				if ( fe.key != null && key.equals( fe.key ) )
                {
                    System.out.println("\t" + fe.features.size() +" features deserialized ok");
					return fe.features;
                }
                else
                {
                    System.out.println("\tKeys did not match");
                    if (key instanceof FloatArray2DSIFT.Param &&
                            fe.key instanceof FloatArray2DSIFT.Param)
                    {
                        FloatArray2DSIFT.Param pkey = (FloatArray2DSIFT.Param)key;
                        FloatArray2DSIFT.Param fepkey = (FloatArray2DSIFT.Param)fe.key;
                        boolean hereEquals, objEquals, paramEquals;

                        System.out.print("fdSize " + pkey.fdSize + " " + fepkey.fdSize + ", ");
                        System.out.print("fdBins " + pkey.fdBins + " " + fepkey.fdBins + ", ");
                        System.out.print("minOctaveSize " + pkey.minOctaveSize + " " + fepkey.minOctaveSize + ", ");
                        System.out.print("maxOctaveSize " + pkey.maxOctaveSize + " " + fepkey.maxOctaveSize + ", ");
                        System.out.print("steps " + pkey.steps + " " + fepkey.steps + ", ");
                        System.out.println("initialSigma " + pkey.initialSigma + " " + fepkey.initialSigma + ", ");

                        hereEquals = pkey.fdSize == fepkey.fdSize;
                        hereEquals &= pkey.fdBins == fepkey.fdBins;
                        hereEquals &= pkey.maxOctaveSize == fepkey.maxOctaveSize;
                        hereEquals &= pkey.minOctaveSize == fepkey.minOctaveSize;
                        hereEquals &= pkey.steps == fepkey.steps;
                        hereEquals &= pkey.initialSigma == fepkey.initialSigma;

                        objEquals = key.equals(fe.key);
                        paramEquals = pkey.equals(fepkey);

                        System.out.println("Object: " + objEquals + ", param: " + paramEquals + ", manual: " + hereEquals);

                        if (hereEquals)
                        {
                            return fe.features;
                        }

                    }
                    else
                    {
                        System.out.println("Did not find FloatArray2DSIFT.Param objects");
                        System.out.println("key: " + key.getClass().getCanonicalName());
                        System.out.println("fe.key: " + fe.key.getClass().getCanonicalName());
                    }
                }
			}
			catch ( final Exception e )
			{
                System.out.println("\tCaught an exception during deserialization");
				Utils.log( "Exception during feature deserialization." );
				e.printStackTrace();
			}
		}
		else
        {
            System.out.println("features file null");
			Utils.log( "features file null" );
        }
		return null;
	}
	
	/**
	 * Save a {@link Collection} of {@link PointMatch PointMatches} two-sided.
	 * Creates two serialization files which is desperately required to clean
	 * up properly invalid serializations on change of a {@link Patch}.
	 * 
	 * @param p
	 * @param t1
	 * @param t2
	 * @param m
	 * @return
	 */
	final static public boolean serializePointMatches(
			final Project project,
			final Object key,
			final String prefix,
			final long id1,
			final long id2,
			final Collection< PointMatch > m )
	{
		final ArrayList< PointMatch > list = new ArrayList< PointMatch >();
		list.addAll( m );
		final ArrayList< PointMatch > tsil = new ArrayList< PointMatch >();
		PointMatch.flip( m, tsil );
		
		final String name = prefix == null ? "pointmatches" : prefix + ".pointmatches";
		
		final Loader loader = project.getLoader();
		return
			loader.serialize(
				new PointMatches( key, list ),
				new StringBuilder( loader.getUNUIdFolder() )
					.append( "pointmatches.ser/" )
					.append( FSLoader.createIdPath( Long.toString( id1 ) + "_" + Long.toString( id2 ), name, ".ser" ) ).toString() ) &&
			loader.serialize(
				new PointMatches( key, tsil ),
				new StringBuilder( loader.getUNUIdFolder() )
					.append( "pointmatches.ser/" )
					.append( FSLoader.createIdPath( Long.toString( id2 ) + "_" + Long.toString( id1 ), name, ".ser" ) ).toString() );
	}
	
	
	final static protected ArrayList< PointMatch > deserializePointMatches(
			final Project project,
			final Object key,
			final String prefix,
			final long id1,
			final long id2 )
	{
		final String name = prefix == null ? "pointmatches" : prefix + ".pointmatches";
		
		final Loader loader = project.getLoader();
		
		final Object ob = loader.deserialize(
				new StringBuilder( loader.getUNUIdFolder() )
					.append( "pointmatches.ser/" )
					.append( FSLoader.createIdPath( Long.toString( id1 ) + "_" + Long.toString( id2 ), name, ".ser" ) ).toString() );
		
		if ( null != ob )
		{
			try
			{
				final PointMatches pm = ( PointMatches )ob;
				if ( pm.key != null && key.equals( pm.key ) )
					return pm.pointMatches;
			}
			catch ( final Exception e )
			{
				Utils.log( "Exception during pointmatch deserialization." );
				e.printStackTrace();
			}
		}
		return null;
	}
	
	
	/**
	 * <p>Transfer and ARGB AWT image into a FloatProcessor with its grey values
	 * and a FloatProcessor with its alpha values as [0...1].</p>
	 * 
	 * <p><em>Note</em>, this method currently relies on how ImageJ reuses the
	 * pixels of an AWT image as generated by {@link Loader#getFlatAWTImage(ini.trakem2.display.Layer, java.awt.Rectangle, double, int, int, Class, java.util.List, boolean, java.awt.Color, ini.trakem2.display.Displayable) Loader.getFlatAWTImage(...)}
	 * for creating a ColorProcessor.  This may change in the future as have
	 * many things in the past.  This method is then the place to fix it. 
	 * 
	 * @param input
	 * @param output
	 * @param alpha
	 */
	final static public void imageToFloatAndMask( final Image input, final FloatProcessor output, final FloatProcessor alpha )
	{
		final ColorProcessor cp = new ColorProcessor( input );
		final int[] inputPixels = ( int[] )cp.getPixels();
		for ( int i = 0; i < inputPixels.length; ++i )
		{
			final int argb = inputPixels[ i ];
			final int a = ( argb >> 24 ) & 0xff;
			final int r = ( argb >> 16 ) & 0xff;
			final int g = ( argb >> 8 ) & 0xff;
			final int b = argb & 0xff;
			
			final float v = ( r + g + b ) / ( float )3;
			final float w = a / ( float )255;
			
			output.setf( i, v );
			alpha.setf( i, w );
		}
	}
	
	
	final static public void applyLayerTransformToPatch( final Patch patch, final CoordinateTransform ct ) throws Exception
	{
		final Rectangle pbox = patch.getCoordinateTransformBoundingBox();
		final AffineTransform pat = new AffineTransform();
		pat.translate( -pbox.x, -pbox.y );
		pat.preConcatenate( patch.getAffineTransform() );
		
		final AffineModel2D toWorld = new AffineModel2D();
		toWorld.set( pat );
		
		final CoordinateTransformList< CoordinateTransform > ctl = new CoordinateTransformList< CoordinateTransform >();
		ctl.add( toWorld );
		ctl.add( ct );
		ctl.add( toWorld.createInverse() );
		
		patch.appendCoordinateTransform( ctl );
	}

	
	final static public AbstractModel< ? > createModel( final int modelIndex )
	{
		switch ( modelIndex )
		{
		case 0:
			return new TranslationModel2D();
		case 1:
			return new RigidModel2D();
		case 2:
			return new SimilarityModel2D();
		case 3:
			return new AffineModel2D();
		case 4:
			return new HomographyModel2D();
		default:
			return null;
		}
	}
}
