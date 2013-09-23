package mpicbg.trakem2.align.concurrent;

/**
 *
 */

import mpicbg.models.Point;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

public class SynchronizeBlockMatchFuture
{
    private final Future<BlockMatchPairCallable.BlockMatchResults> future;
    private final List<? extends Point> v1, v2;

    public SynchronizeBlockMatchFuture(Future<BlockMatchPairCallable.BlockMatchResults> future,
                                       List<? extends Point> v1, List<? extends Point> v2)
    {
        this.future = future;
        this.v1 = v1;
        this.v2 = v2;
    }

    public BlockMatchPairCallable.BlockMatchResults getResults()
            throws InterruptedException, ExecutionException
    {
        BlockMatchPairCallable.BlockMatchResults results = future.get();

        if (results.v1 != v1)
        {
            syncPoints(v1, results.v1);
            results.v1 = v1;
        }

        if (results.v2 != v2)
        {
            syncPoints(v2, results.v2);
            results.v2 = v2;
        }

        return results;
    }

    public static void syncPoints(final List<? extends Point> toSync,
                           final Collection<? extends Point> fromSync)
    {
        int i = 0;
        for (final Point from : fromSync)
        {
            final Point to = toSync.get(i++);
            final float[] wTo = to.getW(), wFrom = from.getW(),
                    lTo = to.getL(), lFrom = from.getL();

            for (int j = 0; j < wTo.length; ++j)
            {
                wTo[j] = wFrom[j];
            }
            for (int j = 0; j < lTo.length; ++j)
            {
                lTo[j] = lFrom[j];
            }
        }
    }

}
