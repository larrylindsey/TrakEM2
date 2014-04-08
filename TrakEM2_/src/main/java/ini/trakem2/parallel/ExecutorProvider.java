package ini.trakem2.parallel;

import java.util.concurrent.ExecutorService;

/**
 * Allow the source ExecutorServices in TrakEM2 to be configured.
 */
public abstract class ExecutorProvider
{

    private static ExecutorProvider localProvider = new DefaultExecutorProvider();
    private static ExecutorProvider provider = localProvider;

    /**
     * Returns an ExecutorService for Callables that use nThreads number of threads.
     * @param nThreads the number of Threads used by a given Callable.
     * @return an ExecutorService that will execute as many Callables as possible for the given
     * number of Threads-per-Callable. For instance, on a machine with 4 cpus (as returned by
     * Runtime.getRuntime().availableProcessors() ), calling getExecutorService(2) will return
     * an ExecutorService that will run 2 ( 4 / 2 ) Callables at a time.
     */
    public static ExecutorService getExecutorService(final String identifier, final int nThreads)
    {
        return provider.getService(identifier, nThreads);
    }

    /**
     * Returns an ExecutorService for Callables that use a given fraction of computer resources.
     * @param fractionThreads the fraction of resources that a submitted Callable is expected to
     *                        need.
     * @return an ExecutorService that will execute as many Callables as possible for the given
     * resource fraction. For instance, getExecutorService(1.0f) will return an ExecutorService
     * that will run only one Callable at a time.
     */

    public static ExecutorService getExecutorService(final String identifier,
                                                     final float fractionThreads)
    {
        return provider.getService(identifier, fractionThreads);
    }

    public static ExecutorService getLocalExecutorService(final String identifier,
                                                          final int nThreads)
    {
         return localProvider.getService(identifier, nThreads);
    }

    public static ExecutorService getLocalExecutorService(final String identifier,
                                                          final float fractionThreads)
    {
        return localProvider.getService(identifier, fractionThreads);
    }

    public static void setProvider(final ExecutorProvider ep)
    {
        provider = ep;
        if (provider.isLocal())
        {
            localProvider = ep;
        }
    }

    public static ExecutorProvider getProvider()
    {
        return provider;
    }

    public abstract ExecutorService getService(final String identifier,
                                               int nThreads);

    public abstract ExecutorService getService(final String identifier,
                                               float fractionThreads);

    public abstract boolean isLocal();
}
