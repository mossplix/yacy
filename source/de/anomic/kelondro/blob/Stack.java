// Stack.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 03.07.2009 on http://yacy.net
//
// $LastChangedDate: 2008-03-14 01:16:04 +0100 (Fr, 14 Mrz 2008) $
// $LastChangedRevision: 4558 $
// $LastChangedBy: orbiter $
//
// LICENSE
// 
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.kelondro.blob;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;

import de.anomic.kelondro.order.NaturalOrder;

public class Stack {

    private Heap stack;
    private long lastHandle;
    
    /**
     * create a new stack object.
     * a stack object is backed by a blob file that contains the stack entries.
     * all stack entries can be accessed with a long handle; the handle is
     * represented as b256-encoded byte[] as key in the blob.
     * The handle is created using the current time. That means that the top
     * element on the stack has the maximum time as key handle and the element
     * at the bottom of the stack has the minimum time as key handle 
     * @param stackFile
     * @throws IOException
     */
    public Stack(final File stackFile) throws IOException {
        this.stack = new Heap(stackFile, 8, NaturalOrder.naturalOrder, 0);
        this.lastHandle = 0;
    }
    
    /**
     * create a new time handle. In case that the method is called
     * within a single millisecond twice, a new handle is created using
     * an increment of the previous handle to avoid handle collisions.
     * This method must be called in an synchronized environment
     * @return a unique handle for this stack
     */
    private long nextHandle() {
        long h = System.currentTimeMillis();
        if (h <= this.lastHandle) h = lastHandle + 1;
        lastHandle = h;
        return h;
    }
    
    /**
     * Iterate all handles from the stack as Long numbers
     * @return an iterator of all handles of the stack
     * @throws IOException
     */
    public synchronized Iterator<Long> handles() throws IOException {
        return NaturalOrder.LongIterator(this.stack.keys(true, false));
    }
    
    /**
     * get the size of a stack
     * @return the number of entries on the stack
     */
    public synchronized int size() {
        return this.stack.size();
    }
    
    /**
     * push a new element on the top of the stack
     * @param b the new stack element
     * @return the handle used to store the new element
     * @throws IOException
     */
    public synchronized long push(byte[] b) throws IOException {
        long handle = nextHandle();
        this.stack.put(NaturalOrder.encodeLong(handle, 8), b);
        return handle;
    }
    
    /**
     * push a new element on the top of the stack using a entry object
     * this is only useful for internal processes where a special handle
     * is created
     * @param b the new stack element
     * @return the handle used to store the new element
     * @throws IOException
     */
    protected synchronized void push(Entry e) throws IOException {
        this.stack.put(NaturalOrder.encodeLong(e.h, 8), e.b);
    }
    
    /**
     * get an element from the stack using the handle
     * @param handle
     * @return the object that belongs to the handle
     *         or null if no such element exists
     * @throws IOException
     */
    public synchronized byte[] get(long handle) throws IOException {
        byte[] k = NaturalOrder.encodeLong(handle, 8);
        byte[] b = this.stack.get(k);
        if (b == null) return null;
        return b;
    }
    
    /**
     * remove an element from the stack using the entry handle
     * @param handle
     * @return the removed element
     * @throws IOException
     */
    public synchronized byte[] remove(long handle) throws IOException {
        byte[] k = NaturalOrder.encodeLong(handle, 8);
        byte[] b = this.stack.get(k);
        if (b == null) return null;
        this.stack.remove(k);
        return b;
    }
    
    /**
     * remove the top element from the stack
     * @return the top element or null if the stack is empty
     * @throws IOException
     */
    public synchronized Entry pop() throws IOException {
        return po(this.stack.lastKey(), true);
    }
    
    /**
     * return the top element of the stack.
     * The element is not removed from the stack.
     * Successive calls to this method will always return the same element
     * @return the element on the top of the stack or null, if stack is empty
     * @throws IOException
     */
    public synchronized Entry top() throws IOException {
        return po(this.stack.lastKey(), false);
    }
    
    /**
     * remove the bottom element from the stack
     * @return the bottom element or null if the stack is empty
     * @throws IOException
     */
    public synchronized Entry pot() throws IOException {
        return po(this.stack.firstKey(), true);
    }
    
    /**
     * return the bottom element of the stack.
     * The element is not removed from the stack.
     * Successive calls to this method will always return the same element
     * @return the element on the bottom of the stack or null, if stack is empty
     * @throws IOException
     */
    public synchronized Entry bot() throws IOException {
        return po(this.stack.firstKey(), false);
    }
    
    private Entry po(byte[] k, boolean remove) throws IOException {
        if (k == null) return null;
        assert k.length == 8;
        byte[] b = this.stack.get(k);
        assert b != null;
        if (b == null) return null;
        if (remove) this.stack.remove(k);
        return new Entry(k, b);
    }
    
    public class Entry {
        
        private long h;
        private byte[] b;
        
        /**
         * create a new entry object using a long handle
         * @param h
         * @param b
         */
        public Entry(long h, byte[] b) {
            this.h = h;
            this.b = b;
        }
        
        /**
         * create a new entry object using the byte[] encoded handle
         * @param k
         * @param b
         */
        public Entry(byte[] k, byte[] b) {
            this.h = NaturalOrder.decodeLong(k);
            this.b = b;
        }
        
        /**
         * get the handle
         * @return the handle
         */
        public long handle() {
            return h;
        }
        
        /**
         * get the blob entry
         * @return the blob
         */
        public byte[] blob() {
            return b;
        }
    }
    
    /**
     * close the stack file and write a handle index
     */
    public synchronized void close() {
        this.stack.close(true);
    }
    
    public void finalize() {
        this.close();
    }
}