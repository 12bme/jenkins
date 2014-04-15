/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.remoting;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Manages unique ID for exported objects, and allows look-up from IDs.
 *
 * @author Kohsuke Kawaguchi
 */
final class ExportTable<T> {
    private final Map<Integer,Entry> table = new HashMap<Integer,Entry>();
    private final Map<T,Entry> reverse = new HashMap<T,Entry>();
    /**
     * {@link ExportList}s which are actively recording the current
     * export operation.
     */
    private final ThreadLocal<ExportList> lists = new ThreadLocal<ExportList>();

    /**
     * For diagnosing problems like JENKINS-20707 where we seem to be unexporting too eagerly,
     * record most recent unexported objects up to {@link #UNEXPORT_LOG_SIZE}
     *
     * New entries are added to the end, and older ones are removed from the beginning.
     */
    private final List<Entry> unexportLog = new LinkedList<Entry>();

    /**
     * Information about one exported object.
     */
    private final class Entry {
        final int id;
        private T object;
        /**
         * Where was this object first exported?
         */
        final CreatedAt allocationTrace;
        /**
         * Where was this object unexported?
         */
        ReleasedAt releaseTrace;
        /**
         * Current reference count.
         * Access to {@link ExportTable} is guarded by synchronized block,
         * so accessing this field requires no further synchronization.
         */
        private int referenceCount;

        Entry(T object) {
            this.id = iota++;
            this.object = object;
            this.allocationTrace = new CreatedAt();

            table.put(id,this);
            reverse.put(object,this);
        }

        void addRef() {
            referenceCount++;
        }

        /**
         * Increase reference count so much to effectively prevent de-allocation.
         * If the reference counting is correct, we just need to increment by one,
         * but this makes it safer even in case of some reference counting errors
         * (and we can still detect the problem by comparing the reference count with the magic value.
         */
        void pin() {
            if (referenceCount<Integer.MAX_VALUE/2)
                referenceCount += Integer.MAX_VALUE/2;
        }

        void release() {
            if(--referenceCount==0) {
                table.remove(id);
                reverse.remove(object);

                // hack to store some information about the object that got unexported
                // don't want to keep the object alive, and toString() seems bit risky
                object = (T)object.getClass().getName();
                releaseTrace = new ReleasedAt();

                unexportLog.add(this);
                while (unexportLog.size()>UNEXPORT_LOG_SIZE)
                    unexportLog.remove(0);
            }
        }

        /**
         * Dumps the contents of the entry.
         */
        void dump(PrintWriter w) throws IOException {
            w.printf("#%d (ref.%d) : %s\n", id, referenceCount, object);
            allocationTrace.printStackTrace(w);
            if (releaseTrace!=null) {
                releaseTrace.printStackTrace(w);
            }
        }

        String dump() {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                dump(pw);
                pw.close();
                return sw.toString();
            } catch (IOException e) {
                throw new Error(e);   // impossible
            }
        }

    }

    static class Source extends Exception {
        protected final long timestamp = System.currentTimeMillis();

        Source() {
            // force the computation of the stack trace in a Java friendly data structure,
            // so that the call stack can be seen from the heap dump after the fact.
            getStackTrace();
        }
    }

    static class CreatedAt extends Source {
        public String toString() {
            return "  Created at "+new Date(timestamp);
        }
    }

    static class ReleasedAt extends Source {
        public String toString() {
            return "  Released at "+new Date(timestamp);
        }
    }

    /**
     * Captures the list of export, so that they can be unexported later.
     *
     * This is tied to a particular thread, so it only records operations
     * on the current thread.
     */
    public final class ExportList extends ArrayList<Entry> {
        private final ExportList old;
        private ExportList() {
            old=lists.get();
            lists.set(this);
        }
        void release() {
            synchronized(ExportTable.this) {
                for (Entry e : this)
                    e.release();
            }
        }
        void stopRecording() {
            lists.set(old);
        }
    }

    /**
     * Unique ID generator.
     */
    private int iota = 1;

    /**
     * Starts the recording of the export operations
     * and returns the list that captures the result.
     *
     * @see ExportList#stopRecording()
     */
    public ExportList startRecording() {
        ExportList el = new ExportList();
        lists.set(el);
        return el;
    }

    public boolean isRecording() {
        return lists.get()!=null;
    }

    /**
     * Exports the given object.
     *
     * <p>
     * Until the object is {@link #unexport(Object) unexported}, it will
     * not be subject to GC.
     *
     * @return
     *      The assigned 'object ID'. If the object is already exported,
     *      it will return the ID already assigned to it.
     */
    public synchronized int export(T t) {
        return export(t,true);
    }

    /**
     * @param notifyListener
     *      If false, listener will not be notified. This is used to
     *      create an export that won't get unexported when the call returns.
     */
    public synchronized int export(T t, boolean notifyListener) {
        if(t==null)    return 0;   // bootstrap classloader

        Entry e = reverse.get(t);
        if(e==null)
            e = new Entry(t);
        e.addRef();

        if(notifyListener) {
            ExportList l = lists.get();
            if(l!=null) l.add(e);
        }

        return e.id;
    }

    /*package*/ synchronized void pin(T t) {
        Entry e = reverse.get(t);
        if(e==null)
            e = new Entry(t);
        e.pin();
    }

    public synchronized @Nonnull T get(int id) {
        Entry e = table.get(id);
        if(e!=null) return e.object;

        throw diagnoseInvalidId(id);
    }

    private synchronized IllegalStateException diagnoseInvalidId(int id) {
        Exception cause=null;

        if (!unexportLog.isEmpty()) {
            for (Entry e : unexportLog) {
                if (e.id==id)
                    cause = new Exception("Object was recently deallocated\n"+e.dump(), e.releaseTrace);
            }
            if (cause==null)
                cause = new Exception("Object appears to be deallocated at lease before "+
                    new Date(unexportLog.get(0).releaseTrace.timestamp));
        }

        return new IllegalStateException("Invalid object ID "+id+" iota="+iota, cause);
    }

    /**
     * Removes the exported object from the table.
     */
    public synchronized void unexport(T t) {
        if(t==null)     return;
        Entry e = reverse.get(t);
        if(e==null) {
            LOGGER.log(SEVERE, "Trying to unexport an object that's not exported: "+t);
            return;
        }
        e.release();
    }

    /**
     * Removes the exported object for the specified oid from the table.
     */
    public synchronized void unexportByOid(Integer oid) {
        if(oid==null)     return;
        Entry e = table.get(oid);
        if(e==null) {
            LOGGER.log(SEVERE, "Trying to unexport an object that's already unexported", diagnoseInvalidId(oid));
            return;
        }
        e.release();
    }

    /**
     * Dumps the contents of the table to a file.
     */
    public synchronized void dump(PrintWriter w) throws IOException {
        for (Entry e : table.values()) {
            e.dump(w);
        }
    }

    /*package*/ synchronized  boolean isExported(T o) {
        return reverse.containsKey(o);
    }

    public static int UNEXPORT_LOG_SIZE = Integer.getInteger(ExportTable.class.getName()+".unexportLogSize",1024);

    private static final Logger LOGGER = Logger.getLogger(ExportTable.class.getName());
}
