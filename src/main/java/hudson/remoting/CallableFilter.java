package hudson.remoting;

/**
 * Decorator on {@code Callable.call()} to filter the execution.
 *
 * @author Kohsuke Kawaguchi
 */
public interface CallableFilter {
    /**
     * This implementation should normally look something like this:
     *
     * <pre>
     * V call(Callable c) {
     *     doSomePrep();
     *     try {
     *         return c.call();
     *     } finally {
     *         doSomeCleanUp();
     *     }
     * }
     * </pre>
     */
    <V> V call(java.util.concurrent.Callable<V> callable) throws Exception;
}
