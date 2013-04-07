package hudson.remoting;

import hudson.remoting.Channel.Mode;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Factory for {@link Channel}, including hand-shaking between two sides
 * and various configuration switches to change the behaviour of {@link Channel}.
 *
 * @author Kohsuke Kawaguchi
 */
public class ChannelBuilder {
    private final String name;
    private final ExecutorService executors;
    private ClassLoader base = this.getClass().getClassLoader();
    private Mode mode = Mode.NEGOTIATE;
    private Capability capability = new Capability();
    private OutputStream header;
    private boolean restricted;

    /**
     * Specify the minimum mandatory parameters.
     *
     * @param name
     *      Human readable name of this channel. Used for debug/logging. Can be anything.
     * @param executors
     *      Commands sent from the remote peer will be executed by using this {@link Executor}.
     */
    public ChannelBuilder(String name, ExecutorService executors) {
        this.name = name;
        this.executors = executors;
    }

    public String getName() {
        return name;
    }

    public ExecutorService getExecutors() {
        return executors;
    }

    /**
     * Specify the classloader used for deserializing remote commands.
     * This is primarily related to {@link Channel#getRemoteProperty(Object)}. Sometimes two parties
     * communicate over a channel and pass objects around as properties, but those types might not be
     * visible from the classloader loading the {@link Channel} class. In such a case, specify a classloader
     * so that those classes resolve. If null, {@code Channel.class.getClassLoader()} is used.
     */
    public ChannelBuilder withBaseLoader(ClassLoader base) {
        if (base==null)     base = this.getClass().getClassLoader();
        this.base = base;
        return this;
    }

    public ClassLoader getBaseLoader() {
        return base;
    }

    /**
     * The encoding to be used over the stream.
     */
    public ChannelBuilder withMode(Mode mode) {
        this.mode = mode;
        return this;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * Controls the capabilities that we'll advertise to the other side.
     */
    public ChannelBuilder withCapability(Capability capability) {
        this.capability = capability;
        return this;
    }

    public Capability getCapability() {
        return capability;
    }

    /**
     * If non-null, receive the portion of data in <tt>is</tt> before
     * the data goes into the "binary mode". This is useful
     * when the established communication channel might include some data that might
     * be useful for debugging/trouble-shooting.
     */
    public ChannelBuilder withHeaderStream(OutputStream header) {
        this.header = header;
        return this;
    }

    public OutputStream getHeaderStream() {
        return header;
    }

    /**
     * If true, this channel won't accept {@link Command}s that allow the remote end to execute arbitrary closures
     * --- instead they can only call methods on objects that are exported by this channel.
     * This also prevents the remote end from loading classes into JVM.
     *
     * Note that it still allows the remote end to deserialize arbitrary object graph
     * (provided that all the classes are already available in this JVM), so exactly how
     * safe the resulting behavior is is up to discussion.
     */
    public ChannelBuilder withRestricted(boolean  restricted) {
        this.restricted = restricted;
        return this;
    }

    public boolean isRestricted() {
        return restricted;
    }

    /**
     * Performs a handshake over the communication channel and builds a {@link Channel}.
     *
     * @param is
     *      Stream connected to the remote peer. It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     * @param os
     *      Stream connected to the remote peer. It's the caller's responsibility to do
     *      buffering on this stream, if that's necessary.
     */
    public Channel build(InputStream is, OutputStream os) throws IOException {
        return new Channel(this,negotiate(is,os));
    }

    public Channel build(CommandTransport transport) throws IOException {
        return new Channel(this,transport);
    }

    /**
     * Performs hand-shaking and creates a {@link CommandTransport}.
     *
     * This is an implementation detail of ChannelBuilder and it's protected
     * just so that
     */
    protected CommandTransport negotiate(InputStream is, OutputStream os) throws IOException {
        // write the magic preamble.
        // certain communication channel, such as forking JVM via ssh,
        // may produce some garbage at the beginning (for example a remote machine
        // might print some warning before the program starts outputting its own data.)
        //
        // so use magic preamble and discard all the data up to that to improve robustness.

        capability.writePreamble(os);

        Mode mode = this.getMode();

        if(mode!= Mode.NEGOTIATE) {
            os.write(mode.preamble);
        }

        {// read the input until we hit preamble
            Mode[] modes={Mode.BINARY,Mode.TEXT};
            byte[][] preambles = new byte[][]{Mode.BINARY.preamble, Mode.TEXT.preamble, Capability.PREAMBLE};
            int[] ptr=new int[3];
            Capability cap = new Capability(0); // remote capacity that we obtained. If we don't hear from remote, assume no capability

            while(true) {
                int ch = is.read();
                if(ch==-1)
                    throw new EOFException("unexpected stream termination");

                for(int i=0;i<preambles.length;i++) {
                    byte[] preamble = preambles[i];
                    if(preamble[ptr[i]]==ch) {
                        if(++ptr[i]==preamble.length) {
                            switch (i) {
                            case 0:
                            case 1:
                                // transmission mode negotiation
                                if(mode==Mode.NEGOTIATE) {
                                    // now we know what the other side wants, so send the consistent preamble
                                    mode = modes[i];
                                    os.write(mode.preamble);
                                } else {
                                    if(modes[i]!=mode)
                                        throw new IOException("Protocol negotiation failure");
                                }

                                return makeTransport(is, os, mode, cap);
                            case 2:
                                cap = Capability.read(is);
                                break;
                            }
                            ptr[i]=0; // reset
                        }
                    } else {
                        // didn't match.
                        ptr[i]=0;
                    }
                }

                if(header!=null)
                    header.write(ch);
            }
        }
    }

    /**
     * Instantiate a transport.
     *
     * @param is
     *      The negotiated input stream that hides
     * @param os
     *      {@linkplain CommandTransport#getUnderlyingStream() the underlying stream}.
     *      @param
     * @param cap
     *      Capabilities of the other side, as determined during the handshaking.
     */
    protected CommandTransport makeTransport(InputStream is, OutputStream os, Mode mode, Capability cap) throws IOException {
        ObjectOutputStream oos = new ObjectOutputStream(mode.wrap(os));
        oos.flush();    // make sure that stream preamble is sent to the other end. avoids dead-lock

        return new ClassicCommandTransport(
                new ObjectInputStreamEx(mode.wrap(is),getBaseLoader()),
                oos,os,cap);
    }
}
