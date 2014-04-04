package mpicbg.trakem2.align.concurrent;

import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ini.trakem2.display.Patch;
import ini.trakem2.utils.Utils;
import mpicbg.ij.blockmatching.BlockMatching;
import mpicbg.models.AbstractModel;
import mpicbg.models.ErrorStatistic;
import mpicbg.models.Point;
import mpicbg.models.PointMatch;
import mpicbg.trakem2.align.AbstractAffineTile2D;
import mpicbg.trakem2.align.ElasticMontage;
import mpicbg.models.InvertibleCoordinateTransform;
import mpicbg.trakem2.util.Triple;
import mpicbg.util.Util;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.Callable;

/**
 *
 */
public class BlockMatchPatchCallable implements Callable<BlockMatchPatchCallable.BlockMatchResults>,
        Serializable
{
    public static class BlockMatchResults implements Serializable
    {

        public final AbstractAffineTile2D<?> t1, t2;
        public final Collection<PointMatch> pm12, pm21;

        public BlockMatchResults(AbstractAffineTile2D<?> t1,
                                 AbstractAffineTile2D<?> t2,
                                 Collection<PointMatch> pm12,
                                 Collection<PointMatch> pm21)
        {
            this.t1 = t1;
            this.t2 = t2;
            this.pm12 = pm12;
            this.pm21 = pm21;
        }
    }

    final static private String patchName( final Patch patch )
    {
        return new StringBuffer( "patch `" )
                .append( patch.getTitle() )
                .append( "'" )
                .toString();
    }

    final static protected FloatProcessor scaleByte( final ByteProcessor bp )
    {
        final FloatProcessor fp = new FloatProcessor( bp.getWidth(), bp.getHeight() );
        final byte[] bytes = ( byte[] )bp.getPixels();
        final float[] floats = ( float[] )fp.getPixels();
        for ( int i = 0; i < bytes.length; ++i )
            floats[ i ] = ( bytes[ i ] & 0xff ) / 255.0f;

        return fp;
    }

    final private Triple<AbstractAffineTile2D<?>, AbstractAffineTile2D<?>, InvertibleCoordinateTransform>
            pair;
    final private ArrayList< AbstractAffineTile2D< ? > > fixedTiles;
    final private ElasticMontage.Param param;
    final private Collection<? extends Point> p1, p2;
    final private AbstractModel<?> localSmoothnessFilterModel;

    public BlockMatchPatchCallable(
            final Triple<AbstractAffineTile2D<?>,
                    AbstractAffineTile2D<?>, InvertibleCoordinateTransform> pair,
            final ArrayList<AbstractAffineTile2D<?>> fixedTiles,
            final ElasticMontage.Param param,
            final Collection<? extends Point> p1,
            final Collection<? extends Point> p2,
            final AbstractModel<?> localSmoothnessFilterModel)
    {
        this.pair = pair;
        this.fixedTiles = fixedTiles;
        this.param = param;
        this.p1 = p1;
        this.p2 = p2;
        this.localSmoothnessFilterModel = localSmoothnessFilterModel;
    }

    @Override
    public BlockMatchResults call() throws Exception
    {
        final AbstractAffineTile2D< ? > t1 = pair.a;
        final AbstractAffineTile2D< ? > t2 = pair.b;

        final int blockRadius = Math.max( Util.roundPos(16 / param.bmScale), param.bmBlockRadius );

        /** TODO set this something more than the largest error by the approximate model */
        final int searchRadius = param.bmSearchRadius;

        final String patchName1 = patchName(t1.getPatch());
        final String patchName2 = patchName(t2.getPatch());

        final Patch.PatchImage pi1 = t1.getPatch().createTransformedImage();

        if ( pi1 == null )
        {
            Utils.log( "Patch `" + patchName1 + "' failed generating a transformed image.  Skipping..." );
            return null;
        }

        final Patch.PatchImage pi2 = t2.getPatch().createTransformedImage();

        if ( pi2 == null )
        {
            Utils.log( "Patch `" + patchName2 + "' failed generating a transformed image.  Skipping..." );
            return null;
        }


        final FloatProcessor fp1 = ( FloatProcessor )pi1.target.convertToFloat();
        final ByteProcessor mask1 = pi1.getMask();
        final FloatProcessor fpMask1 = mask1 == null ? null : scaleByte( mask1 );

        final FloatProcessor fp2 = ( FloatProcessor )pi2.target.convertToFloat();
        final ByteProcessor mask2 = pi2.getMask();
        final FloatProcessor fpMask2 = mask2 == null ? null : scaleByte( mask2 );

        final ArrayList< PointMatch > pm12 = new ArrayList< PointMatch >();
        final ArrayList< PointMatch > pm21 = new ArrayList< PointMatch >();


        if ( !fixedTiles.contains( t1 ) )
        {
            BlockMatching.matchByMaximalPMCC(
                    fp1,
                    fp2,
                    fpMask1,
                    fpMask2,
                    param.bmScale,
                    pair.c,
                    blockRadius,
                    blockRadius,
                    searchRadius,
                    searchRadius,
                    param.bmMinR,
                    param.bmRodR,
                    param.bmMaxCurvatureR,
                    p1,
                    pm12,
                    new ErrorStatistic(1));

            if ( param.bmUseLocalSmoothnessFilter )
            {
                Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondence candidates." );
                localSmoothnessFilterModel.localSmoothnessFilter( pm12, pm12, param.bmLocalRegionSigma, param.bmMaxLocalEpsilon, param.bmMaxLocalTrust );
                Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': " + pm12.size() + " candidates passed local smoothness filter." );
            }
            else
            {
                Utils.log( "`" + patchName1 + "' > `" + patchName2 + "': found " + pm12.size() + " correspondences." );
            }
        }
        else
        {
            Utils.log( "Skipping fixed patch `" + patchName1 + "'." );
        }

//			/* <visualisation> */
//			//			final List< Point > s1 = new ArrayList< Point >();
//			//			PointMatch.sourcePoints( pm12, s1 );
//			//			final ImagePlus imp1 = new ImagePlus( i + " >", ip1 );
//			//			imp1.show();
//			//			imp1.setOverlay( BlockMatching.illustrateMatches( pm12 ), Color.yellow, null );
//			//			imp1.setRoi( Util.pointsToPointRoi( s1 ) );
//			//			imp1.updateAndDraw();
//			/* </visualisation> */

        if ( !fixedTiles.contains( t2 ) )
        {
            BlockMatching.matchByMaximalPMCC(
                    fp2,
                    fp1,
                    fpMask2,
                    fpMask1,
                    param.bmScale,
                    pair.c.createInverse(),
                    blockRadius,
                    blockRadius,
                    searchRadius,
                    searchRadius,
                    param.bmMinR,
                    param.bmRodR,
                    param.bmMaxCurvatureR,
                    p2,
                    pm21,
                    new ErrorStatistic( 1 ) );

            if ( param.bmUseLocalSmoothnessFilter )
            {
                Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': found " + pm21.size() + " correspondence candidates." );
                localSmoothnessFilterModel.localSmoothnessFilter( pm21, pm21, param.bmLocalRegionSigma, param.bmMaxLocalEpsilon, param.bmMaxLocalTrust );
                Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': " + pm21.size() + " candidates passed local smoothness filter." );
            }
            else
            {
                Utils.log( "`" + patchName1 + "' < `" + patchName2 + "': found " + pm21.size() + " correspondences." );
            }
        }
        else
        {
            Utils.log( "Skipping fixed patch `" + patchName2 + "'." );
        }
        return new BlockMatchResults(pair.a, pair.b, pm12, pm21);
    }
}
