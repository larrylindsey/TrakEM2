package ini.trakem2.parallel;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Default Executor Provider, which creates ExecutorServices from java.util.concurrent.Executors
 *
 * @author Larry Lindsey
 */
public class DefaultExecutorProvider extends ExecutorProvider
{
    private final Map<String, Map<Integer, ExecutorService>> serviceMap;

    public DefaultExecutorProvider()
    {
        serviceMap = Collections.synchronizedMap(
                new HashMap<String,Map<Integer, ExecutorService>>());
    }

    public ExecutorService getService(final String identifier, final int nThreads)
    {
        ExecutorService es = null;

        if (serviceMap.containsKey(identifier))
        {
            if (serviceMap.get(identifier).containsKey(nThreads))
            {
                es = serviceMap.get(identifier).get(nThreads);
            }
        }
        else
        {
            serviceMap.put(identifier,
                    Collections.synchronizedMap(new HashMap<Integer, ExecutorService>()));
        }

        if (es == null)
        {
            System.out.println("Creating new service for " + identifier + " with "
                    + nThreads + " threads");
            int nCpu = Runtime.getRuntime().availableProcessors();
            int poolSize = nCpu / nThreads;
            es = Executors.newFixedThreadPool(poolSize < 1 ? 1 : poolSize);
            serviceMap.get(identifier).put(nThreads, es);
        }
        else
        {
            System.out.println("Returning existing service for " + identifier + " with "
                    + nThreads + " threads");
        }

        return es;
    }

    public ExecutorService getService(final String identifier, float fractionThreads)
    {
        int nThreads = (int)(fractionThreads * (float)Runtime.getRuntime().availableProcessors());
        return getService(identifier, nThreads);
    }

    public boolean isLocal()
    {
        return true;
    }
}
