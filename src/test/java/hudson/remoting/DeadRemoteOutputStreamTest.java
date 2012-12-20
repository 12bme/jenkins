package hudson.remoting;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * @author Kohsuke Kawaguchi
 */
public class DeadRemoteOutputStreamTest extends RmiTestBase implements Serializable {
    private static final class DeadIOException extends IOException {}

    /**
     * If the remote writing end reports {@link IOException}, then the writing end shall
     * eventually see it.
     */
    public void testDeadWriterNotification() throws Exception {
        final OutputStream os = new RemoteOutputStream(new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                System.gc();
                throw new DeadIOException();
            }
        });

        channel.call(new Callable<Void, Exception>() {
            public Void call() throws Exception {
                os.write(0); // this write will go through because we won't notice that it's dead
                System.gc();
                Thread.sleep(1000);

                try {
                    for (int i=0; i<100; i++) {
                        os.write(0);
                        System.gc();
                        Thread.sleep(10);
                    }
                    fail("Expected to see the failure");
                } catch (IOException e) {
                    assertTrue(e.getCause() instanceof DeadIOException);
                }
                return null;
            }
        });
    }


}
