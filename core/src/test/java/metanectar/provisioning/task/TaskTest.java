package metanectar.provisioning.task;

import java.util.concurrent.*;

/**
 * @author Paul Sandoz
 */
public class TaskTest extends TaskTestBase {

    class SleepTaskState {
        int n;

        SleepTaskState(int n) {
            this.n = n;
        }
    }

    class SleepTask extends SomeTask {

        final long sleepTime;

        final SleepTaskState s;

        SleepTask(long timeout, long sleepTime) {
            this(timeout, sleepTime, new SleepTaskState(0));
        }

        SleepTask(long timeout, long sleepTime, SleepTaskState s) {
            super(timeout);
            this.sleepTime = sleepTime;
            this.s = s;
        }

        public Callable<Object> createWork() {
            return new Callable<Object>() {
                public Object call() throws Exception {
                    Thread.currentThread().sleep(sleepTime);
                    return null;
                }
            };
        }

        public SleepTask end() throws Exception {
            super.end();

            if (s.n == 0) {
                return null;
            }

            s.n--;
            return new SleepTask(getTimeout(), sleepTime, s);
        }
    }

    class SleepTaskException extends Exception {}

    class SleepTaskWithException extends SleepTask {

        SleepTaskWithException(long timeout, long sleepTime) {
            super(timeout, sleepTime, new SleepTaskState(0));
        }

        public Callable<Object> createWork() {
            return new Callable<Object>() {
                public Object call() throws Exception {
                    Thread.currentThread().sleep(sleepTime);
                    throw new SleepTaskException();
                }
            };
        }
    }

    public void testOneTask() throws Exception {
        TaskQueue q = new TaskQueue();
        Future<?> ft = q.add(new SleepTask(TimeUnit.MINUTES.toMillis(1), 100));
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        ft.get(1, TimeUnit.MINUTES);
        assertFalse(ft.isCancelled());
    }

    public void testOneTaskWithExplicitFutureTask() throws Exception {
        TaskQueue q = new TaskQueue();
        FutureTaskEx<String> fte = new FutureTaskEx<String>(new Callable<String>() {
            public String call() throws Exception {
                return "FINISHED";
            }
        });

        Future<String> ft = q.add(new SleepTask(TimeUnit.MINUTES.toMillis(1), 100), fte);
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        String s = ft.get(1, TimeUnit.MINUTES);
        assertFalse(ft.isCancelled());
        assertEquals("FINISHED", s);
    }

    public void testOneTaskWithException() throws Exception {
        TaskQueue q = new TaskQueue();
        Future<?> ft = q.add(new SleepTaskWithException(TimeUnit.MINUTES.toMillis(1), 100));
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        Throwable caught = null;
        try {
            ft.get(1, TimeUnit.MINUTES);
        } catch (ExecutionException e) {
            caught = e.getCause();
        }
        assertNotNull(caught);
        assertTrue(caught instanceof SleepTaskException);
    }

    public void testOneTaskWithCancelBeforeStart() throws Exception {
        TaskQueue q = new TaskQueue();
        SleepTask st = new SleepTask(TimeUnit.MINUTES.toMillis(1), 100);
        Future<?> ft = q.add(st);
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        ft.cancel(true);
        Future<?> fq = processQueue(q, 50);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        Throwable caught = null;
        try {
            ft.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            caught = e;
        }
        assertNull(caught);

        assertTrue(st.isDone());
    }

    class SleepTaskWithLatchOnStart extends SleepTask {
        CountDownLatch c = new CountDownLatch(1);

        SleepTaskWithLatchOnStart(long timeout, long sleepTime) {
            super(timeout, sleepTime, new SleepTaskState(0));
        }

        protected void setFuture(Future f) {
            super.setFuture(f);
            c.countDown();
        }
    }

    public void testOneTaskWithCancelAfterStart() throws Exception {
        TaskQueue q = new TaskQueue();
        SleepTaskWithLatchOnStart st = new SleepTaskWithLatchOnStart(TimeUnit.MINUTES.toMillis(1), 100);
        Future<?> ft = q.add(st);
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        st.c.await(1, TimeUnit.MINUTES);
        ft.cancel(true);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        Throwable caught = null;
        try {
            ft.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            caught = e;
        }
        assertNull(caught);

        assertTrue(st.isDone());
    }

    public void testOneTaskWithCancelTaskAfterStart() throws Exception {
        TaskQueue q = new TaskQueue();
        SleepTaskWithLatchOnStart st = new SleepTaskWithLatchOnStart(TimeUnit.MINUTES.toMillis(1), 100);
        Future<?> ft = q.add(st);
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        st.c.await(1, TimeUnit.MINUTES);
        st.cancel();

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        assertTrue(ft.isCancelled());
        Throwable caught = null;
        try {
            ft.get(1, TimeUnit.MINUTES);
        } catch (Exception e) {
            caught = e;
        }
        assertNotNull(caught);
        assertTrue(caught instanceof CancellationException);

        assertTrue(st.isStarted());
        assertTrue(st.isCancelled());
    }

    public void testChainingTask() throws Exception {
        TaskQueue q = new TaskQueue();
        SleepTaskState s = new SleepTaskState(2);
        Future<?> ft = q.add(new SleepTask(TimeUnit.MINUTES.toMillis(1), 100, s));
        assertFalse(ft.isDone());
        assertFalse(ft.isCancelled());

        Future<?> fq = processQueue(q, 50);

        fq.get(1, TimeUnit.MINUTES);
        assertFalse(fq.isCancelled());

        ft.get(1, TimeUnit.MINUTES);
        assertFalse(ft.isCancelled());

        assertEquals(0, s.n);
    }

}
