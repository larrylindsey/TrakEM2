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

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
import ij.process.FloatProcessor;
import ini.trakem2.Project;
import ini.trakem2.display.*;
import ini.trakem2.persistence.FloatProcessorSASE;
import ini.trakem2.utils.AreaUtils;
import ini.trakem2.utils.Filter;
import ini.trakem2.utils.Utils;

import java.awt.Color;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.geom.Area;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.imagefeatures.Feature;
import mpicbg.imagefeatures.FloatArray2DSIFT;
import mpicbg.models.AbstractModel;
import mpicbg.models.AffineModel2D;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.HomographyModel2D;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.models.NotEnoughDataPointsException;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.models.RigidModel2D;
import mpicbg.models.SimilarityModel2D;
import mpicbg.models.Spring;
import mpicbg.models.SpringMesh;
import mpicbg.models.Tile;
import mpicbg.models.TileConfiguration;
import mpicbg.models.Transforms;
import mpicbg.models.TranslationModel2D;
import mpicbg.models.Vertex;
import mpicbg.trakem2.concurrent.ThreadPool;
import mpicbg.trakem2.transform.MovingLeastSquaresTransform2;
import mpicbg.trakem2.util.Triple;

/**
 * @author Stephan Saalfeld <saalfeld@mpi-cbg.de>
 */
public class ElasticLayerAlignment
{

    private static AtomicLong idGenerator = new AtomicLong(1);
    private Hashtable<Long, Point> pointCache = new Hashtable<Long, Point>();

    protected synchronized void cachePoints(Collection<? extends Point> points)
    {
        for (Point p : points)
        {
            if (p.getID() == 0)
            {
                p.setID(idGenerator.getAndIncrement());
                pointCache.put(p.getID(), p);
            }
        }
    }

    protected synchronized void syncPointMatch(Collection<PointMatch> pointMatches)
    {
        for (PointMatch pm : pointMatches)
        {
            final Point p1 = pm.getP1(), p2 = pm.getP2();
            if (p1.getID() != 0)
            {
                final Point mp1 = pointCache.get(p1.getID());
                if (mp1 != p1)
                {
                    mp1.set(p1);
                    pm.setP1(mp1);
                }
            }

            if (p2.getID() != 0)
            {
                final Point mp2 = pointCache.get(p2.getID());
                if (mp2 != p2)
                {
                    mp2.set(p2);
                    pm.setP2(mp2);
                }
            }
        }
    }

    protected synchronized void clearPointCache()
    {
        pointCache.clear();
    }


    
    protected static class PMCResults implements Serializable
    {
        final public ArrayList<PointMatch> pm12, pm21;
        final public Triple<Integer, Integer, AbstractModel<?>> pair;
        final public boolean layer1fixed, layer2fixed;
        public boolean needsSync;

        public PMCResults(final ArrayList<PointMatch> pm12,
                          final ArrayList<PointMatch> pm21,
                          Triple<Integer, Integer, AbstractModel<?>> pair,
                          final boolean layer1fixed,
                          final boolean layer2fixed)
        {
            this.pm12 = pm12;
            this.pm21 = pm21;
            this.pair = pair;
            this.layer1fixed = layer1fixed;
            this.layer2fixed = layer2fixed;
            this.needsSync = false;

        }

        private void readObject(ObjectInputStream input) throws ClassNotFoundException, IOException
        {
            input.defaultReadObject();
            this.needsSync = true;
        }

    }

    protected static class PointMatchCallable implements Callable<PMCResults>, Serializable
    {
        private final Triple<Integer, Integer, AbstractModel<?>> pair;
        private transient Project project;
        private transient List<Layer> layerRange;
        private transient Filter<Patch> filter;
        private transient Rectangle box;

        private boolean fpsInitted;

        private final boolean layer1Fixed, layer2Fixed;

        private final int blockRadius;
        private final int searchRadius;
        private final float minR, maxCurvatureR, rodR, layerScale;

        private final Collection<Vertex> v1, v2;

        private FloatProcessorSASE fp1, fp2, fp1Mask, fp2Mask;

        public PointMatchCallable(final Triple<Integer, Integer, AbstractModel<?>> pair,
                                  final Project project,
                                  final List<Layer> layerRange,                                  
                                  final Filter<Patch> filter,
                                  final Rectangle box,
                                  final Collection<Vertex> v1,
                                  final Collection<Vertex> v2,
                                  final Param p,
                                  final boolean layer1Fixed,
                                  final boolean layer2Fixed)
        {
            this.pair = pair;
            this.project = project;
            this.layerRange = layerRange;
            this.filter = filter;
            this.box = box;
            this.v1 = v1;
            this.v2 = v2;
            this.layer1Fixed = layer1Fixed;
            this.layer2Fixed = layer2Fixed;

            fp1 = null;
            fp2 = null;
            fp1Mask = null;
            fp2Mask = null;

            fpsInitted = false;

            blockRadius = Math.max( 16, mpicbg.util.Util.roundPos( p.layerScale * p.blockRadius ) );

            /* scale pixel distances */
            searchRadius = ( int )Math.round( p.layerScale * p.searchRadius );

            rodR = p.rodR;
            maxCurvatureR = p.maxCurvatureR;
            minR = p.minR;
            layerScale = p.layerScale;
            
        }

        private void writeObject(ObjectOutputStream outStream) throws IOException
        {
            if (!fpsInitted)
            {
                generateFPCs();
            }

            outStream.defaultWriteObject();

            release();
        }

        private void release()
        {
            fp1 = null;
            fp2 = null;
            fp1Mask = null;
            fp2Mask = null;
            fpsInitted = false;
            System.gc();
        }

        private void generateFPCs()                
        {
            if (! (layer1Fixed && layer2Fixed))
            {
                final Layer layer1 =  layerRange.get( pair.a );
                final Layer layer2 =  layerRange.get( pair.b );
                project.getLoader().releaseAll();

                final Image img1 = project.getLoader().getFlatAWTImage(
                        layer1,
                        box,
                        layerScale,
                        0xffffffff,
                        ImagePlus.COLOR_RGB,
                        Patch.class,
                        AlignmentUtils.filterPatches( layer1, filter ),
                        true,
                        new Color( 0x00ffffff, true ) );

                final Image img2 = project.getLoader().getFlatAWTImage(
                        layer2,
                        box,
                        layerScale,
                        0xffffffff,
                        ImagePlus.COLOR_RGB,
                        Patch.class,
                        AlignmentUtils.filterPatches( layer2, filter ),
                        true,
                        new Color( 0x00ffffff, true ) );

                final int width = img1.getWidth( null );
                final int height = img1.getHeight( null );

                final FloatProcessor ip1 = new FloatProcessor( width, height );
                final FloatProcessor ip2 = new FloatProcessor( width, height );
                final FloatProcessor ip1Mask = new FloatProcessor( width, height );
                final FloatProcessor ip2Mask = new FloatProcessor( width, height );

                mpicbg.trakem2.align.Util.imageToFloatAndMask( img1, ip1, ip1Mask );
                mpicbg.trakem2.align.Util.imageToFloatAndMask( img2, ip2, ip2Mask );

                fp1 = new FloatProcessorSASE(ip1);
                fp2 = new FloatProcessorSASE(ip2);
                fp1Mask = new FloatProcessorSASE(ip1Mask);
                fp2Mask = new FloatProcessorSASE(ip2Mask);
            }
            fpsInitted = true;
        }


        public PMCResults call() throws Exception
        {
            if (!fpsInitted)
            {
                generateFPCs();
            }

            ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
            ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();

            if (!layer1Fixed)
            {
                BlockMatching.matchByMaximalPMCC(
                        fp1.getData(),
                        fp2.getData(),
                        fp1Mask.getData(),
                        fp2Mask.getData(),
                        1.0f,
                        ( ( InvertibleCoordinateTransform )pair.c ).createInverse(),
                        blockRadius,
                        blockRadius,
                        searchRadius,
                        searchRadius,
                        minR,
                        rodR,
                        maxCurvatureR,
                        v1,
                        pm12,
                        new ErrorStatistic( 1 ) );
            }

            if (!layer2Fixed)
            {
                BlockMatching.matchByMaximalPMCC(
                        fp2.getData(),
                        fp1.getData(),
                        fp2Mask.getData(),
                        fp1Mask.getData(),
                        1.0f,
                        pair.c,
                        blockRadius,
                        blockRadius,
                        searchRadius,
                        searchRadius,
                        minR,
                        rodR,
                        maxCurvatureR,
                        v2,
                        pm21,
                        new ErrorStatistic( 1 ) );
            }
            
            release();

            System.out.println("Done");

            return new PMCResults(pm12, pm21, pair, layer1Fixed, layer2Fixed);
        }
    }




	final static public class Param extends AbstractLayerAlignmentParam implements Serializable
	{
		private static final long serialVersionUID = 398966126705836033L;

		public boolean isAligned = false;
		
		public float layerScale = 0.1f;
		public float minR = 0.6f;
		public float maxCurvatureR = 10f;
		public float rodR = 0.9f;
		public int searchRadius = 200;
		public int blockRadius = -1;
		
		public boolean useLocalSmoothnessFilter = true;
		public int localModelIndex = 1;
		public float localRegionSigma = searchRadius;
		public float maxLocalEpsilon = searchRadius / 2;
		public float maxLocalTrust = 3;
		
		public int resolutionSpringMesh = 16;
		public float stiffnessSpringMesh = 0.1f;
		public float dampSpringMesh = 0.6f;
		public float maxStretchSpringMesh = 2000.0f;
		public int maxIterationsSpringMesh = 1000;
		public int maxPlateauwidthSpringMesh = 200;
		
		public boolean setup( final Rectangle box )
		{
			/* Block Matching */
			if ( blockRadius < 0 )
			{
				blockRadius = box.width / resolutionSpringMesh / 2;
			}
			final GenericDialog gdBlockMatching = new GenericDialog( "Elastically align layers: Block Matching parameters" );
			
			gdBlockMatching.addMessage( "Block Matching:" );
			/* TODO suggest isotropic resolution for this parameter */
			gdBlockMatching.addNumericField( "layer_scale :", layerScale, 2 );
			gdBlockMatching.addNumericField( "search_radius :", searchRadius, 0, 6, "px" );
			gdBlockMatching.addNumericField( "block_radius :", blockRadius, 0, 6, "px" );
			/* TODO suggest a resolution that matches searchRadius */
			gdBlockMatching.addNumericField( "resolution :", resolutionSpringMesh, 0 );
			
			gdBlockMatching.addMessage( "Correlation Filters:" );
			gdBlockMatching.addNumericField( "minimal_PMCC_r :", minR, 2 );
			gdBlockMatching.addNumericField( "maximal_curvature_ratio :", maxCurvatureR, 2 );
			gdBlockMatching.addNumericField( "maximal_second_best_r/best_r :", rodR, 2 );
			
			gdBlockMatching.addMessage( "Local Smoothness Filter:" );
			gdBlockMatching.addCheckbox( "use_local_smoothness_filter", useLocalSmoothnessFilter );
			gdBlockMatching.addChoice( "approximate_local_transformation :", Param.modelStrings, Param.modelStrings[ localModelIndex ] );
			gdBlockMatching.addNumericField( "local_region_sigma:", localRegionSigma, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (absolute):", maxLocalEpsilon, 2, 6, "px" );
			gdBlockMatching.addNumericField( "maximal_local_displacement (relative):", maxLocalTrust, 2 );
			
			gdBlockMatching.addMessage( "Miscellaneous:" );
			gdBlockMatching.addCheckbox( "layers_are_pre-aligned", isAligned );
			gdBlockMatching.addNumericField( "test_maximally :", maxNumNeighbors, 0, 6, "layers" );
			
			gdBlockMatching.showDialog();
			
			if ( gdBlockMatching.wasCanceled() )
				return false;
			
			layerScale = ( float )gdBlockMatching.getNextNumber();
			searchRadius = ( int )gdBlockMatching.getNextNumber();
			blockRadius = ( int )gdBlockMatching.getNextNumber();
			resolutionSpringMesh = ( int )gdBlockMatching.getNextNumber();
			minR = ( float )gdBlockMatching.getNextNumber();
			maxCurvatureR = ( float )gdBlockMatching.getNextNumber();
			rodR = ( float )gdBlockMatching.getNextNumber();
			useLocalSmoothnessFilter = gdBlockMatching.getNextBoolean();
			localModelIndex = gdBlockMatching.getNextChoiceIndex();
			localRegionSigma = ( float )gdBlockMatching.getNextNumber();
			maxLocalEpsilon = ( float )gdBlockMatching.getNextNumber();
			maxLocalTrust = ( float )gdBlockMatching.getNextNumber();
			isAligned = gdBlockMatching.getNextBoolean();
			maxNumNeighbors = ( int )gdBlockMatching.getNextNumber();
			
			
			if ( !isAligned )
			{
				if ( !setupSIFT( "Elastically align layers: " ) )
					return false;
				
				/* Geometric filters */
				
				final GenericDialog gd = new GenericDialog( "Elastically align layers: Geometric filters" );
				
				gd.addNumericField( "maximal_alignment_error :", maxEpsilon, 2, 6, "px" );
				gd.addNumericField( "minimal_inlier_ratio :", minInlierRatio, 2 );
				gd.addNumericField( "minimal_number_of_inliers :", minNumInliers, 0 );
				gd.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ expectedModelIndex ] );
				gd.addCheckbox( "ignore constant background", rejectIdentity );
				gd.addNumericField( "tolerance :", identityTolerance, 2, 6, "px" );
				gd.addNumericField( "give_up_after :", maxNumFailures, 0, 6, "failures" );
				
				gd.showDialog();
				
				if ( gd.wasCanceled() )
					return false;
				
				maxEpsilon = ( float )gd.getNextNumber();
				minInlierRatio = ( float )gd.getNextNumber();
				minNumInliers = ( int )gd.getNextNumber();
				expectedModelIndex = gd.getNextChoiceIndex();
				rejectIdentity = gd.getNextBoolean();
				identityTolerance = ( float )gd.getNextNumber();
				maxNumFailures = ( int )gd.getNextNumber();
			}
			
			
			/* Optimization */
			final GenericDialog gdOptimize = new GenericDialog( "Elastically align layers: Optimization" );
			
			gdOptimize.addMessage( "Approximate Optimizer:" );
			gdOptimize.addChoice( "approximate_transformation :", Param.modelStrings, Param.modelStrings[ desiredModelIndex ] );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsOptimize, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthOptimize, 0 );
			
			gdOptimize.addMessage( "Spring Mesh:" );
			gdOptimize.addNumericField( "stiffness :", stiffnessSpringMesh, 2 );
			gdOptimize.addNumericField( "maximal_stretch :", maxStretchSpringMesh, 2, 6, "px" );
			gdOptimize.addNumericField( "maximal_iterations :", maxIterationsSpringMesh, 0 );
			gdOptimize.addNumericField( "maximal_plateauwidth :", maxPlateauwidthSpringMesh, 0 );
			
			gdOptimize.showDialog();
			
			if ( gdOptimize.wasCanceled() )
				return false;
			
			desiredModelIndex = gdOptimize.getNextChoiceIndex();
			maxIterationsOptimize = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthOptimize = ( int )gdOptimize.getNextNumber();
			
			stiffnessSpringMesh = ( float )gdOptimize.getNextNumber();
			maxStretchSpringMesh = ( float )gdOptimize.getNextNumber();
			maxIterationsSpringMesh = ( int )gdOptimize.getNextNumber();
			maxPlateauwidthSpringMesh = ( int )gdOptimize.getNextNumber();
			
			return true;
		}
		
		public Param() {}
		
		public Param(
				final int SIFTfdBins,
				final int SIFTfdSize,
				final float SIFTinitialSigma,
				final int SIFTmaxOctaveSize,
				final int SIFTminOctaveSize,
				final int SIFTsteps,
				
				final boolean clearCache,
				final int maxNumThreadsSift,
				final float rod,
				
				final int desiredModelIndex,
				final int expectedModelIndex,
				final float identityTolerance,
				final boolean isAligned,
				final float maxEpsilon,
				final int maxIterationsOptimize,
				final int maxNumFailures,
				final int maxNumNeighbors,
				final int maxNumThreads,
				final int maxPlateauwidthOptimize,
				final float minInlierRatio,
				final int minNumInliers,
				final boolean multipleHypotheses,
				final boolean rejectIdentity,
				final boolean visualize,
				
				final int blockRadius,
				final float dampSpringMesh,
				final float layerScale,
				final int localModelIndex,
				final float localRegionSigma,
				final float maxCurvatureR,
				final int maxIterationsSpringMesh,
				final float maxLocalEpsilon,
				final float maxLocalTrust,
				final int maxPlateauwidthSpringMesh,
				final float maxStretchSpringMesh,
				final float minR,
				final int resolutionSpringMesh,
				final float rodR,
				final int searchRadius,
				final float stiffnessSpringMesh,
				final boolean useLocalSmoothnessFilter )
		{
			super(
					SIFTfdBins,
					SIFTfdSize,
					SIFTinitialSigma,
					SIFTmaxOctaveSize,
					SIFTminOctaveSize,
					SIFTsteps,
					clearCache,
					maxNumThreadsSift,
					rod,
					desiredModelIndex,
					expectedModelIndex,
					identityTolerance,
					maxEpsilon,
					maxIterationsOptimize,
					maxNumFailures,
					maxNumNeighbors,
					maxNumThreads,
					maxPlateauwidthOptimize,
					minInlierRatio,
					minNumInliers,
					multipleHypotheses,
					rejectIdentity,
					visualize );
			
			this.isAligned = isAligned;
			this.blockRadius = blockRadius;
			this.dampSpringMesh = dampSpringMesh;
			this.layerScale = layerScale;
			this.localModelIndex = localModelIndex;
			this.localRegionSigma = localRegionSigma;
			this.maxCurvatureR = maxCurvatureR;
			this.maxIterationsSpringMesh = maxIterationsSpringMesh;
			this.maxLocalEpsilon = maxLocalEpsilon;
			this.maxLocalTrust = maxLocalTrust;
			this.maxPlateauwidthSpringMesh = maxPlateauwidthSpringMesh;
			this.maxStretchSpringMesh = maxStretchSpringMesh;
			this.minR = minR;
			this.resolutionSpringMesh = resolutionSpringMesh;
			this.rodR = rodR;
			this.searchRadius = searchRadius;
			this.stiffnessSpringMesh = stiffnessSpringMesh;
			this.useLocalSmoothnessFilter = useLocalSmoothnessFilter;
		}
		
		@Override
		public Param clone()
		{
			return new Param(
					ppm.sift.fdBins,
					ppm.sift.fdSize,
					ppm.sift.initialSigma,
					ppm.sift.maxOctaveSize,
					ppm.sift.minOctaveSize,
					ppm.sift.steps,
					
					ppm.clearCache,
					ppm.maxNumThreadsSift,
					ppm.rod,
					
					desiredModelIndex,
					expectedModelIndex,
					identityTolerance,
					isAligned,
					maxEpsilon,
					maxIterationsOptimize,
					maxNumFailures,
					maxNumNeighbors,
					maxNumThreads,
					maxPlateauwidthOptimize,
					minInlierRatio,
					minNumInliers,
					multipleHypotheses,
					rejectIdentity,
					visualize,
					
					blockRadius,
					dampSpringMesh,
					layerScale,
					localModelIndex,
					localRegionSigma,
					maxCurvatureR,
					maxIterationsSpringMesh,
					maxLocalEpsilon,
					maxLocalTrust,
					maxPlateauwidthSpringMesh,
					maxStretchSpringMesh,
					minR,
					resolutionSpringMesh,
					rodR,
					searchRadius,
					stiffnessSpringMesh,
					useLocalSmoothnessFilter );
		}
	}
	
	final static Param p = new Param();

	
	final static private String layerName( final Layer layer )
	{
		return new StringBuffer( "layer z=" )
			.append( String.format( "%.3f", layer.getZ() ) )
			.append( " `" )
			.append( layer.getTitle() )
			.append( "'" )
			.toString();
		
	}
	
	
	/**
	 * 
	 * @param param
	 * @param layerRange
	 * @param fixedLayers
	 * @param emptyLayers
	 * @param box
	 * @param filter
	 * @throws Exception
	 */
	final public void exec(
			final Param param,
			final Project project,
			final List< Layer > layerRange,
			final Set< Layer > fixedLayers,
			final Set< Layer > emptyLayers,
			final Rectangle box,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Filter< Patch > filter ) throws Exception
	{
		final double scale = Math.min( 1.0, Math.min( ( double )param.ppm.sift.maxOctaveSize / ( double )box.width, ( double )param.ppm.sift.maxOctaveSize / ( double )box.height ) );
		
        System.out.println("Your are using TrakEM Larry Rev B");
		
		/* create tiles and models for all layers */
		final ArrayList< Tile< ? > > tiles = new ArrayList< Tile< ? > >();
		for ( int i = 0; i < layerRange.size(); ++i )
		{
			switch ( param.desiredModelIndex )
			{
			case 0:
				tiles.add( new Tile< TranslationModel2D >( new TranslationModel2D() ) );
				break;
			case 1:
				tiles.add( new Tile< RigidModel2D >( new RigidModel2D() ) );
				break;
			case 2:
				tiles.add( new Tile< SimilarityModel2D >( new SimilarityModel2D() ) );
				break;
			case 3:
				tiles.add( new Tile< AffineModel2D >( new AffineModel2D() ) );
				break;
			case 4:
				tiles.add( new Tile< HomographyModel2D >( new HomographyModel2D() ) );
				break;
			default:
				return;
			}
		}
		
		/* collect all pairs of slices for which a model could be found */
		final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > pairs = new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >();
		
		
		if ( !param.isAligned )
		{
		
			/* extract and save features, overwrite cached files if requested */
			try
			{
				AlignmentUtils.extractAndSaveLayerFeatures( layerRange, box, scale, filter, param.ppm.sift, param.ppm.clearCache, param.ppm.maxNumThreadsSift );
			}
			catch ( final Exception e )
			{
				return;
			}
		
			/* match and filter feature correspondences */
			int numFailures = 0;
			
			final double pointMatchScale = param.layerScale / scale;
			
			for ( int i = 0; i < layerRange.size(); ++i )
			{
				final ArrayList< Thread > threads = new ArrayList< Thread >( param.maxNumThreads );
				
				final int sliceA = i;
				final Layer layerA = layerRange.get( i );
				final int range = Math.min( layerRange.size(), i + param.maxNumNeighbors + 1 );
				
				final String layerNameA = layerName( layerA );
				
J:				for ( int j = i + 1; j < range; )
				{
					final int numThreads = Math.min( param.maxNumThreads, range - j );
					final ArrayList< Triple< Integer, Integer, AbstractModel< ? > > > models =
						new ArrayList< Triple< Integer, Integer, AbstractModel< ? > > >( numThreads );
					
					for ( int k = 0; k < numThreads; ++k )
						models.add( null );
					
					for ( int t = 0;  t < numThreads && j < range; ++t, ++j )
					{
						final int ti = t;
						final int sliceB = j;
						final Layer layerB = layerRange.get( j );
						
						final String layerNameB = layerName( layerB );
						
						final Thread thread = new Thread()
						{
							@Override
							public void run()
							{
								IJ.showProgress( sliceA, layerRange.size() - 1 );
								
								Utils.log( "matching " + layerNameB + " -> " + layerNameA + "..." );
								
								ArrayList< PointMatch > candidates = null;
								if ( !param.ppm.clearCache )
									candidates = mpicbg.trakem2.align.Util.deserializePointMatches(
											project, param.ppm, "layer", layerB.getId(), layerA.getId() );
								
								if ( null == candidates )
								{
									final ArrayList< Feature > fs1 = mpicbg.trakem2.align.Util.deserializeFeatures(
											project, param.ppm.sift, "layer", layerA.getId() );
									final ArrayList< Feature > fs2 = mpicbg.trakem2.align.Util.deserializeFeatures(
											project, param.ppm.sift, "layer", layerB.getId() );
									candidates = new ArrayList< PointMatch >( FloatArray2DSIFT.createMatches( fs2, fs1, param.ppm.rod ) );
									
									/* scale the candidates */
									for ( final PointMatch pm : candidates )
									{
										final Point p1 = pm.getP1();
										final Point p2 = pm.getP2();
										final float[] l1 = p1.getL();
										final float[] w1 = p1.getW();
										final float[] l2 = p2.getL();
										final float[] w2 = p2.getW();
										
										l1[ 0 ] *= pointMatchScale;
										l1[ 1 ] *= pointMatchScale;
										w1[ 0 ] *= pointMatchScale;
										w1[ 1 ] *= pointMatchScale;
										l2[ 0 ] *= pointMatchScale;
										l2[ 1 ] *= pointMatchScale;
										w2[ 0 ] *= pointMatchScale;
										w2[ 1 ] *= pointMatchScale;
										
									}
									
									if ( !mpicbg.trakem2.align.Util.serializePointMatches(
											project, param.ppm, "layer", layerB.getId(), layerA.getId(), candidates ) )
										Utils.log( "Could not store point match candidates for layers " + layerNameB + " and " + layerNameA + "." );
								}
			
								AbstractModel< ? > model;
								switch ( param.expectedModelIndex )
								{
								case 0:
									model = new TranslationModel2D();
									break;
								case 1:
									model = new RigidModel2D();
									break;
								case 2:
									model = new SimilarityModel2D();
									break;
								case 3:
									model = new AffineModel2D();
									break;
								case 4:
									model = new HomographyModel2D();
									break;
								default:
									return;
								}
								
								final ArrayList< PointMatch > inliers = new ArrayList< PointMatch >();
								
								boolean modelFound;
								boolean again = false;
								try
								{
									do
									{
										again = false;
										modelFound = model.filterRansac(
													candidates,
													inliers,
													1000,
													param.maxEpsilon * param.layerScale,
													param.minInlierRatio,
													param.minNumInliers,
													3 );
										if ( modelFound && param.rejectIdentity )
										{
											final ArrayList< Point > points = new ArrayList< Point >();
											PointMatch.sourcePoints( inliers, points );
											if ( Transforms.isIdentity( model, points, param.identityTolerance *  param.layerScale ) )
											{
												IJ.log( "Identity transform for " + inliers.size() + " matches rejected." );
												candidates.removeAll( inliers );
												inliers.clear();
												again = true;
											}
										}
									}
									while ( again );
								}
								catch ( final NotEnoughDataPointsException e )
								{
									modelFound = false;
								}
								
								if ( modelFound )
								{
									Utils.log( layerNameB + " -> " + layerNameA + ": " + inliers.size() + " corresponding features with an average displacement of " + ( PointMatch.meanDistance( inliers ) / param.layerScale ) + "px identified." );
									Utils.log( "Estimated transformation model: " + model );
									models.set( ti, new Triple< Integer, Integer, AbstractModel< ? > >( sliceA, sliceB, model ) );
								}
								else
								{
									Utils.log( layerNameB + " -> " + layerNameA + ": no correspondences found." );
									return;
								}
							}
						};
						threads.add( thread );
						thread.start();
					}
					
					try
					{
						for ( final Thread thread : threads )
							thread.join();
					}
					catch ( final InterruptedException e )
					{
						Utils.log( "Establishing feature correspondences interrupted." );
						for ( final Thread thread : threads )
							thread.interrupt();
						try
						{
							for ( final Thread thread : threads )
								thread.join();
						}
						catch ( final InterruptedException f ) {}
						return;
					}
					
					threads.clear();
					
					/* collect successfully matches pairs and break the search on gaps */
					for ( int t = 0; t < models.size(); ++t )
					{
						final Triple< Integer, Integer, AbstractModel< ? > > pair = models.get( t );
						if ( pair == null )
						{
							if ( ++numFailures > param.maxNumFailures )
								break J;
						}
						else
						{
							numFailures = 0;
							pairs.add( pair );
						}
					}
				}
			}
		}
		else
		{
			for ( int i = 0; i < layerRange.size(); ++i )
			{
				final int range = Math.min( layerRange.size(), i + param.maxNumNeighbors + 1 );
				
				for ( int j = i + 1; j < range; ++j )
				{
					pairs.add( new Triple< Integer, Integer, AbstractModel< ? > >( i, j, new TranslationModel2D() ) );
				}
			}
		}
		
		/* Elastic alignment */
		
		/* Initialization */
		final TileConfiguration initMeshes = new TileConfiguration();
		
		final int meshWidth = ( int )Math.ceil( box.width * param.layerScale );
		final int meshHeight = ( int )Math.ceil( box.height * param.layerScale );
		
		final ArrayList< SpringMesh > meshes = new ArrayList< SpringMesh >( layerRange.size() );
		for ( int i = 0; i < layerRange.size(); ++i )
			meshes.add(
					new SpringMesh(
							param.resolutionSpringMesh,
							meshWidth,
							meshHeight,
							param.stiffnessSpringMesh,
							param.maxStretchSpringMesh * param.layerScale,
							param.dampSpringMesh ) );
		
		//final int blockRadius = Math.max( 32, meshWidth / p.resolutionSpringMesh / 2 );
		final int blockRadius = Math.max( 16, mpicbg.util.Util.roundPos( param.layerScale * param.blockRadius ) );
		
		Utils.log( "effective block radius = " + blockRadius );
		
		/* scale pixel distances */
		//final int searchRadius = ( int )Math.round( param.layerScale * param.searchRadius );
		final float localRegionSigma = param.layerScale * param.localRegionSigma;
		final float maxLocalEpsilon = param.layerScale * param.maxLocalEpsilon;
		
		final AbstractModel< ? > localSmoothnessFilterModel = Util.createModel( param.localModelIndex );
		
        final ExecutorService executor = ThreadPool.getExecutorService(1.0f);
        
		final ArrayList<Future<PMCResults>> results = new ArrayList<Future<PMCResults>>(pairs.size());

        for (SpringMesh mesh : meshes)
        {
            cachePoints(mesh.getVertices());
        }
        
		for ( final Triple< Integer, Integer, AbstractModel< ? > > pair : pairs )
		{
			/* free memory */
			project.getLoader().releaseAll();
			
			final SpringMesh m1 = meshes.get( pair.a );
			final SpringMesh m2 = meshes.get( pair.b );

			final Collection< Vertex > v1 = m1.getVertices();
			final Collection< Vertex > v2 = m2.getVertices();
			
			final Layer layer1 = layerRange.get( pair.a );
			final Layer layer2 = layerRange.get( pair.b );
			
			final boolean layer1Fixed = fixedLayers.contains( layer1 );
			final boolean layer2Fixed = fixedLayers.contains( layer2 );
			
                results.add(executor.submit(new PointMatchCallable(
                        pair,
                        project,
                        layerRange,
                        filter,
                        box,
                        v1,
                        v2,
                        param,
                        layer1Fixed,
                        layer2Fixed)));
        }

        for (Future<PMCResults> future : results)
        {

            PMCResults pmcResult;
            try
            {
                pmcResult = future.get();
            }
            catch (ExecutionException ee)
            {
                Utils.log( "Block matching interrupted." );
                IJ.showProgress( 1.0 );
                return;
            }
            catch (InterruptedException ie)
            {
                Utils.log( "Block matching interrupted." );
                IJ.showProgress( 1.0 );
                return;
            }

            final Triple<Integer, Integer, AbstractModel<?>> pair = pmcResult.pair;
            final float springConstant  = 1.0f / ( pair.b - pair.a );
            boolean layer1Fixed = pmcResult.layer1fixed;
            boolean layer2Fixed = pmcResult.layer2fixed;
            final Tile<?> t1 = tiles.get(pair.a);
            final Tile<?> t2 = tiles.get(pair.b);
            final SpringMesh m1 = meshes.get( pair.a );
            final SpringMesh m2 = meshes.get( pair.b );

            final ArrayList< PointMatch > pm12 = pmcResult.pm12;
            final ArrayList< PointMatch > pm21 = pmcResult.pm21;

            if ( !( layer1Fixed && layer2Fixed ))
            {

                if (pmcResult.needsSync)
                {
                    syncPointMatch(pmcResult.pm12);
                    syncPointMatch(pmcResult.pm21);
                }

                if ( layer1Fixed )
                {
                    initMeshes.fixTile( t1 );
                }
                else
                {

                    if ( param.useLocalSmoothnessFilter )
                    {
                        Utils.log( pair.a + " > " + pair.b + ": found " + pm12.size() + " correspondence candidates." );
                        localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, localRegionSigma, maxLocalEpsilon, param.maxLocalTrust );
                        Utils.log( pair.a + " > " + pair.b + ": " + pm12.size() + " candidates passed local smoothness filter." );
                    }
                    else
                    {
                        Utils.log( pair.a + " > " + pair.b + ": found " + pm12.size() + " correspondences." );
                    }

                    /* <visualisation> */
                    //			final List< Point > s1 = new ArrayList< Point >();
                    //			PointMatch.sourcePoints( pm12, s1 );
                    //			final ImagePlus imp1 = new ImagePlus( i + " >", ip1 );
                    //			imp1.show();
                    //			imp1.setOverlay( BlockMatching.illustrateMatches( pm12 ), Color.yellow, null );
                    //			imp1.setRoi( Util.pointsToPointRoi( s1 ) );
                    //			imp1.updateAndDraw();
                    /* </visualisation> */

                    for ( final PointMatch pm : pm12 )
                    {
                        final Vertex p1 = ( Vertex )pm.getP1();
                        final Vertex p2 = new Vertex( pm.getP2() );
                        p1.addSpring( p2, new Spring( 0, springConstant ) );
                        m2.addPassiveVertex( p2 );
                    }

                    /*
                    * adding Tiles to the initialing TileConfiguration, adding a Tile
                    * multiple times does not harm because the TileConfiguration is
                    * backed by a Set.
                    */
                    if ( pm12.size() > pair.c.getMinNumMatches() )
                    {
                        initMeshes.addTile( t1 );
                        initMeshes.addTile( t2 );
                        t1.connect( t2, pm12 );
                    }
                }

                if ( layer2Fixed )
                    initMeshes.fixTile( t2 );
                else
                {
//                catch ( final InterruptedException e )
//                {
//                    Utils.log( "Block matching interrupted." );
//                    IJ.showProgress( 1.0 );
//                    return;
//                }
//                if ( Thread.interrupted() )
//                {
//                    Utils.log( "Block matching interrupted." );
//                    IJ.showProgress( 1.0 );
//                    return;
//                }

                    if ( param.useLocalSmoothnessFilter )
                    {
                        Utils.log( pair.a + " < " + pair.b + ": found " + pm21.size() + " correspondence candidates." );
                        localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, localRegionSigma, maxLocalEpsilon, param.maxLocalTrust );
                        Utils.log( pair.a + " < " + pair.b + ": " + pm21.size() + " candidates passed local smoothness filter." );
                    }
                    else
                    {
                        Utils.log( pair.a + " < " + pair.b + ": found " + pm21.size() + " correspondences." );
                    }

                    /* <visualisation> */
                    //			final List< Point > s2 = new ArrayList< Point >();
                    //			PointMatch.sourcePoints( pm21, s2 );
                    //			final ImagePlus imp2 = new ImagePlus( i + " <", ip2 );
                    //			imp2.show();
                    //			imp2.setOverlay( BlockMatching.illustrateMatches( pm21 ), Color.yellow, null );
                    //			imp2.setRoi( Util.pointsToPointRoi( s2 ) );
                    //			imp2.updateAndDraw();
                    /* </visualisation> */

                    for ( final PointMatch pm : pm21 )
                    {
                        final Vertex p1 = ( Vertex )pm.getP1();
                        final Vertex p2 = new Vertex( pm.getP2() );
                        p1.addSpring( p2, new Spring( 0, springConstant ) );
                        m1.addPassiveVertex( p2 );
                    }

                    /*
                    * adding Tiles to the initialing TileConfiguration, adding a Tile
                    * multiple times does not harm because the TileConfiguration is
                    * backed by a Set.
                    */
                    if ( pm21.size() > pair.c.getMinNumMatches() )
                    {
                        initMeshes.addTile( t1 );
                        initMeshes.addTile( t2 );
                        t2.connect( t1, pm21 );
                    }
                }

                Utils.log( pair.a + " <> " + pair.b + " spring constant = " + springConstant );
            }
        }

        clearPointCache();

		/* pre-align by optimizing a piecewise linear model */ 
		initMeshes.optimize(
				param.maxEpsilon * param.layerScale,
				param.maxIterationsSpringMesh,
				param.maxPlateauwidthSpringMesh );
		for ( int i = 0; i < layerRange.size(); ++i )
			meshes.get( i ).init( tiles.get( i ).getModel() );

		/* optimize the meshes */
		try
		{
			final long t0 = System.currentTimeMillis();
			Utils.log("Optimizing spring meshes...");
			
			SpringMesh.optimizeMeshes(
					meshes,
					param.maxEpsilon * param.layerScale,
					param.maxIterationsSpringMesh,
					param.maxPlateauwidthSpringMesh,
					param.visualize );

			Utils.log("Done optimizing spring meshes. Took " + (System.currentTimeMillis() - t0) + " ms");
			
		}
		catch ( final NotEnoughDataPointsException e )
		{
			Utils.log( "There were not enough data points to get the spring mesh optimizing." );
			e.printStackTrace();
			return;
		}
		
		/* translate relative to bounding box */
		for ( final SpringMesh mesh : meshes )
		{
			for ( final PointMatch pm : mesh.getVA().keySet() )
			{
				final Point p1 = pm.getP1();
				final Point p2 = pm.getP2();
				final float[] l = p1.getL();
				final float[] w = p2.getW();
				l[ 0 ] = l[ 0 ] / param.layerScale + box.x;
				l[ 1 ] = l[ 1 ] / param.layerScale + box.y;
				w[ 0 ] = w[ 0 ] / param.layerScale + box.x;
				w[ 1 ] = w[ 1 ] / param.layerScale + box.y;
			}
		}
		
		/* free memory */
		project.getLoader().releaseAll();
		
		final Layer first = layerRange.get( 0 );
		final List< Layer > layers = first.getParent().getLayers();

        final LayerSet ls = first.getParent();
        final List<VectorData> vectorData = new ArrayList<VectorData>();
        for (final Layer layer : ls.getLayers()) {
            vectorData.addAll(
                    Utils.castCollection(layer.getDisplayables(VectorData.class, false, true),
                            VectorData.class, true));
        }
        vectorData.addAll(Utils.castCollection(ls.getZDisplayables(VectorData.class, true),
                VectorData.class, true));


//
//        vectorData.addAll(first.getParent().getAll(AreaList.class));
//        vectorData.addAll(first.getParent().getAll(Ball.class));
//        vectorData.addAll(first.getParent().getAll(Dissector.class));
//        vectorData.addAll(first.getParent().getAll(Pipe.class));
//        vectorData.addAll(first.getParent().getAll(Polyline.class));
//        vectorData.addAll(first.getParent().getAll(Tree.class));
        
        Area infArea = AreaUtils.infiniteArea();
        
		/* transfer layer transform into patch transforms and append to patches */
		if ( propagateTransformBefore || propagateTransformAfter )
		{
			if ( propagateTransformBefore )
			{
				final MovingLeastSquaresTransform2 mlt = makeMLST2( meshes.get( 0 ).getVA().keySet() );
				final int firstLayerIndex = first.getParent().getLayerIndex( first.getId() );
				for ( int i = 0; i < firstLayerIndex; ++i )
                {
					applyTransformToLayer( layers.get( i ), mlt, filter );
                    for (VectorData vectorDatum : vectorData)
                    {
                        vectorDatum.apply(layers.get(i), infArea, mlt);
                    }
                }
			}
			if ( propagateTransformAfter )
			{
				final Layer last = layerRange.get( layerRange.size() - 1 );
				final MovingLeastSquaresTransform2 mlt = makeMLST2( meshes.get( meshes.size() - 1 ).getVA().keySet() );
				final int lastLayerIndex = last.getParent().getLayerIndex( last.getId() );
				for ( int i = lastLayerIndex + 1; i < layers.size(); ++i )
                {
					applyTransformToLayer( layers.get( i ), mlt, filter );
                    for (VectorData vectorDatum : vectorData)
                    {
                        vectorDatum.apply(layers.get(i), infArea, mlt);
                    }
                }
			}
		}
		for ( int l = 0; l < layerRange.size(); ++l )
		{
			IJ.showStatus( "Applying transformation to patches ..." );
			IJ.showProgress( 0, layerRange.size() );
			
			final Layer layer = layerRange.get( l );
			
			final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
			mlt.setModel( AffineModel2D.class );
			mlt.setAlpha( 2.0f );
			mlt.setMatches( meshes.get( l ).getVA().keySet() );

            for (VectorData vectorDatum : vectorData)
            {
                vectorDatum.apply(layer, infArea, mlt);
            }

            for (DLabel dl : layer.getAll(DLabel.class))
            {
                dl.apply(layer, infArea, mlt);
            }

            for (Profile p : layer.getAll(Profile.class))
            {
                p.apply(layer, infArea, mlt);
            }


            applyTransformToLayer( layer, mlt, filter );
					
			if ( Thread.interrupted() )
			{
				Utils.log( "Interrupted during applying transformations to patches.  No all patches have been updated.  Re-generate mipmaps manually." );
			}
			
			IJ.showProgress( l + 1, layerRange.size() );
		}
		
		/* update patch mipmaps */
		final int firstLayerIndex;
		final int lastLayerIndex;
		
		if ( propagateTransformBefore )
			firstLayerIndex = 0;
		else
		{
			firstLayerIndex = first.getParent().getLayerIndex( first.getId() );
		}
		if ( propagateTransformAfter )
			 lastLayerIndex = layers.size() - 1;
		else
		{
			final Layer last = layerRange.get( layerRange.size() - 1 );
			lastLayerIndex = last.getParent().getLayerIndex( last.getId() );
		}
		
		for ( int i = firstLayerIndex; i <= lastLayerIndex; ++i )
		{
			final Layer layer = layers.get( i );
			if ( !( emptyLayers.contains( layer ) || fixedLayers.contains( layer ) ) )
			{
				for ( final Patch patch : AlignmentUtils.filterPatches( layer, filter ) )
					patch.updateMipMaps();
			}
		}
		
		Utils.log( "Done." );
	}
	
	final static protected MovingLeastSquaresTransform2 makeMLST2( final Set< PointMatch > matches ) throws Exception
	{
		final MovingLeastSquaresTransform2 mlt = new MovingLeastSquaresTransform2();
		mlt.setModel( AffineModel2D.class );
		mlt.setAlpha( 2.0f );
		mlt.setMatches( matches );
		return mlt;
	}
	
	final static protected void applyTransformToLayer( final Layer layer, final MovingLeastSquaresTransform2 mlt, final Filter< Patch > filter ) throws InterruptedException
	{
		/*
		 * Setting a transformation to a patch can take some time because
		 * the new bounding box needs to be estimated which requires the
		 * TransformMesh to be generated and all vertices iterated.
		 * 
		 * Therefore multithreading.
		 */
		final List< Patch > patches = AlignmentUtils.filterPatches( layer, filter );
		
		final ArrayList< Thread > applyThreads = new ArrayList< Thread >( p.maxNumThreads );
		final AtomicInteger ai = new AtomicInteger( 0 );
		for ( int t = 0; t < p.maxNumThreads; ++t )
		{
			final Thread thread = new Thread(
					new Runnable()
					{
						//@Override
						final public void run()
						{
							try
							{
								for ( int i = ai.getAndIncrement(); i < patches.size() && !Thread.interrupted(); i = ai.getAndIncrement() )
									mpicbg.trakem2.align.Util.applyLayerTransformToPatch( patches.get( i ), mlt.copy() );
							}
							catch ( final Exception e )
							{
								e.printStackTrace();
							}
						}
					} );
			applyThreads.add( thread );
			thread.start();
		}
		
		for ( final Thread thread : applyThreads )
			thread.join();
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 *
	 * @param layerRange
	 * @param fixedLayers
	 * @param propagateTransformBefore
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 * @throws Exception
	 */
	final public void exec(
			final Project project,
			final List< Layer > layerRange,
			final Set< Layer > fixedLayers,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{	
		Rectangle box = null;
		final HashSet< Layer > emptyLayers = new HashSet< Layer >();
		for ( final Iterator< Layer > it = layerRange.iterator(); it.hasNext(); )
		{
			/* remove empty layers */
			final Layer la = it.next();
			if ( !la.contains( Patch.class, true ) )
			{
				emptyLayers.add( la );
//				it.remove();
			}
			else
			{
				/* accumulate boxes */
				if ( null == box ) // The first layer:
					box = la.getMinimalBoundingBox( Patch.class, true );
				else
					box = box.union( la.getMinimalBoundingBox( Patch.class, true ) );
			}
		}
		
		if ( box == null )
			box = new Rectangle();
		
		if ( fov != null )
			box = box.intersection( fov );
		
		if ( box.width <= 0 || box.height <= 0 )
		{
			Utils.log( "Bounding box empty." );
			return;
		}
		
		if ( layerRange.size() == emptyLayers.size() )
		{
			Utils.log( "All layers in range are empty!" );
			return;
		}
		
		/* do not work if there is only one layer selected */
		if ( layerRange.size() - emptyLayers.size() < 2 )
		{
			Utils.log( "All except one layer in range are empty!" );
			return;
		}

		if ( !p.setup( box ) ) return;
		
		exec( p.clone(), project, layerRange, fixedLayers, emptyLayers, box, propagateTransformBefore, propagateTransformAfter, filter );
	}
	

	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param propagateTransformBefore
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final HashSet< Layer > fixedLayers = new HashSet< Layer >();
		
		if ( ref - firstIn >= 0 )
			fixedLayers.add( layerRange.get( ref - firstIn ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerSet.getProject(), layerRange, fixedLayers, propagateTransformBefore, propagateTransformAfter, fov, filter );
	}
	
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param ref
	 * @param propagateTransform
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref,
			final boolean propagateTransform,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		if ( firstIn < lastIn )
			exec( layerSet, firstIn, lastIn, ref, false, propagateTransform, fov, filter );
		else
			exec( layerSet, firstIn, lastIn, ref, propagateTransform, false, fov, filter );
	}
	
	/**
	 * Stateful.  Changing the parameters of this instance.  Do not use in parallel.
	 * 
	 * @param layerSet
	 * @param firstIn
	 * @param lastIn
	 * @param ref1
	 * @param ref2
	 * @param propagateTransformAfter
	 * @param fov
	 * @param filter
	 */
	final public void exec(
			final LayerSet layerSet,
			final int firstIn,
			final int lastIn,
			final int ref1,
			final int ref2,
			final boolean propagateTransformBefore,
			final boolean propagateTransformAfter,
			final Rectangle fov,
			final Filter< Patch > filter ) throws Exception
	{
		final int first = Math.min( firstIn, lastIn );
		final int last = Math.max( firstIn, lastIn );
		
		/* always first index first despite the method would return inverse order if last > first */
		final List< Layer > layerRange = layerSet.getLayers( first, last );
		final HashSet< Layer > fixedLayers = new HashSet< Layer >();
		
		if ( ref1 - first >= 0 )
			fixedLayers.add( layerRange.get( ref1 - first ) );
		if ( ref2 - first >= 0 )
			fixedLayers.add( layerRange.get( ref2 - first ) );
		
		Utils.log( layerRange.size() + "" );
		
		exec( layerSet.getProject(), layerRange, fixedLayers, propagateTransformBefore, propagateTransformAfter, fov, filter );
	}
}
