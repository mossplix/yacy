// ReverseIndexCell.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 1.3.2009 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2009-01-02 12:38:20 +0100 (Fr, 02 Jan 2009) $
// $LastChangedRevision: 5432 $
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

package de.anomic.kelondro.text;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.order.CloneableIterator;
import de.anomic.kelondro.order.MergeIterator;
import de.anomic.kelondro.order.Order;

/*
 * an index cell is a part of the horizontal index in the new segment-oriented index
 * data structure of YaCy. If there is no filter in front of a cell, it might also be
 * the organization for a complete segment index. Each cell consists of a number of BLOB files, that
 * must be merged to represent a single index. In fact these index files are only merged on demand
 * if there are too many of them. An index merge can be done with a stream read and stream write operation.
 * in normal operation, there are only a number of read-only BLOB files and a single RAM cache that is
 * kept in the RAM as long as a given limit of entries is reached. Then the cache is flushed and becomes
 * another BLOB file in the index array.
 */

public final class IndexCell implements Index {

    // class variables
    private ReferenceContainerArray array;
    private ReferenceContainerCache ram;
    private int maxRamEntries;
    
    public IndexCell(
            final File cellPath,
            final Row payloadrow,
            final int maxRamEntries
            ) throws IOException {
        this.array = new ReferenceContainerArray(cellPath, payloadrow);
        this.ram = new ReferenceContainerCache(payloadrow);
        this.maxRamEntries = maxRamEntries;
    }
    
    private void cacheDump() throws IOException {
        // dump the ram
        File dumpFile = this.array.newContainerBLOBFile();
        this.ram.dump(dumpFile);
        // get a fresh ram cache
        this.ram = new ReferenceContainerCache(this.array.rowdef());
        // add the dumped indexContainerBLOB to the array
        this.array.mountBLOBContainer(dumpFile);
    }

    /**
     * add entries to the cell: this adds the new entries always to the RAM part, never to BLOBs
     * @throws IOException 
     */
    public synchronized void addReferences(ReferenceContainer newEntries) throws IOException {
        this.ram.addReferences(newEntries);
        if (this.ram.size() > this.maxRamEntries) cacheDump();
    }

    /**
     * clear the RAM and BLOB part, deletes everything in the cell
     */
    public synchronized void clear() throws IOException {
        this.ram.clear();
        this.array.clear();
    }

    /**
     * when a cell is closed, the current RAM is dumped to a file which will be opened as
     * BLOB file the next time a cell is opened. A name for the dump is automatically generated
     * and is composed of the current date and the cell salt
     */
    public synchronized void close() {
        // dump the ram
        try {
            this.ram.dump(this.array.newContainerBLOBFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
        // close all
        this.ram.close();
        this.array.close();
    }

    /**
     * deleting a container affects the containers in RAM and all the BLOB files
     * the deleted containers are merged and returned as result of the method
     */
    public ReferenceContainer deleteAllReferences(String wordHash) throws IOException {
        ReferenceContainer c0 = this.ram.deleteAllReferences(wordHash);
        ReferenceContainer c1 = this.array.get(wordHash);
        if (c1 == null) {
            if (c0 == null) return null;
            return c0;
        }
        this.array.delete(wordHash);
        if (c0 == null) return c1;
        return c1.merge(c0);
    }

    /**
     * all containers in the BLOBs and the RAM are merged and returned
     */
    public ReferenceContainer getReferences(String wordHash, Set<String> urlselection) throws IOException {
        ReferenceContainer c0 = this.ram.getReferences(wordHash, null);
        ReferenceContainer c1 = this.array.get(wordHash);
        if (c1 == null) {
            if (c0 == null) return null;
            return c0;
        }
        if (c0 == null) return c1;
        return c1.merge(c0);
    }

    public int countReferences(String wordHash) {
        ReferenceContainer c0 = this.ram.getReferences(wordHash, null);
        ReferenceContainer c1;
        try {
            c1 = this.array.get(wordHash);
        } catch (IOException e) {
            c1 = null;
        }
        if (c1 == null) {
            if (c0 == null) return 0;
            return c0.size();
        }
        if (c0 == null) return c1.size();
        return c1.size() + c0.size();
    }
    
    /**
     * checks if there is any container for this wordHash, either in RAM or any BLOB
     */
    public boolean hasReferences(String wordHash) {
        if (this.ram.hasReferences(wordHash)) return true;
        return this.array.has(wordHash);
    }

    public int minMem() {
        return 10 * 1024 * 1024;
    }

    /**
     * remove url references from a selected word hash. this deletes also in the BLOB
     * files, which means that there exists new gap entries after the deletion
     * The gaps are never merged in place, but can be eliminated when BLOBs are merged into
     * new BLOBs. This returns the sum of all url references that have been removed
     * @throws IOException 
     * @throws IOException 
     */
    public int removeReferences(String wordHash, Set<String> urlHashes) throws IOException {
        int reduced = this.array.replace(wordHash, new RemoveRewriter(urlHashes));
        return reduced / this.array.rowdef().objectsize;
    }

    public boolean removeReference(String wordHash, String urlHash) throws IOException {
        int reduced = this.array.replace(wordHash, new RemoveRewriter(urlHash));
        return reduced > 0;
    }

    public int size() {
        return this.ram.size() + this.array.size();
    }

    public CloneableIterator<ReferenceContainer> referenceIterator(String startWordHash, boolean rot, boolean ram) throws IOException {
        final Order<ReferenceContainer> containerOrder = new ReferenceContainerOrder(this.ram.rowdef().getOrdering().clone());
        containerOrder.rotate(new ReferenceContainer(startWordHash, this.ram.rowdef(), 0));
        if (ram) {
            return this.ram.referenceIterator(startWordHash, rot, true);
        }
        return new MergeIterator<ReferenceContainer>(
                this.ram.referenceIterator(startWordHash, false, true),
                this.array.wordContainerIterator(startWordHash, false, false),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true);
    }

    private static class RemoveRewriter implements ReferenceContainerArray.ContainerRewriter {
        
        Set<String> urlHashes;
        
        public RemoveRewriter(Set<String> urlHashes) {
            this.urlHashes = urlHashes;
        }
        
        public RemoveRewriter(String urlHash) {
            this.urlHashes = new HashSet<String>();
            this.urlHashes.add(urlHash);
        }
        
        public ReferenceContainer rewrite(ReferenceContainer container) {
            container.removeEntries(urlHashes);
            return container;
        }
        
    }
    
}