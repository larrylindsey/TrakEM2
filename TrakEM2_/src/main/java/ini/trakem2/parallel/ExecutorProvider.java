package ini.trakem2.parallel;

import java.util.concurrent.ExecutorService;

/**
 * Allow the source ExecutorServices in TrakEM2 to be configured.
 */
public abstract class ExecutorProvider
{

    private static ExecutorProvider provider = new DefaultExecutorProvider();

    public static ExecutorService getExecutorService(final int nThreads)
    {
        return provider.getService(nThreads);
    }

    public static ExecutorService getExecutorService(final float fractionThreads)
    {
        return provider.getService(fractionThreads);
    }

    public static void setProvider(final ExecutorProvider ep)
    {
        provider = ep;
    }

    public static ExecutorProvider getProvider()
    {
        return provider;
    }

    public abstract ExecutorService getService(int nThreads);

    public abstract ExecutorService getService(float fractionThreads);

    static {
        System.out.println("Executor Provider!");
    }
}
