// IndexCollectionMigration.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 30.03.2009 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2009-03-13 11:34:51 +0100 (Fr, 13 Mrz 2009) $
// $LastChangedRevision: 5709 $
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
import java.util.Set;

import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.order.ByteOrder;
import de.anomic.kelondro.order.CloneableIterator;
import de.anomic.kelondro.order.MergeIterator;
import de.anomic.kelondro.order.Order;
import de.anomic.kelondro.order.RotateIterator;
import de.anomic.kelondro.text.Index;
import de.anomic.kelondro.text.IndexCollection;
import de.anomic.kelondro.text.ReferenceContainer;
import de.anomic.kelondro.text.ReferenceContainerOrder;
import de.anomic.kelondro.text.referencePrototype.WordReferenceRow;
import de.anomic.kelondro.util.FileUtils;
import de.anomic.kelondro.util.Log;

public final class IndexCollectionMigration<ReferenceType extends Reference> extends AbstractBufferedIndex<ReferenceType> implements Index<ReferenceType>, BufferedIndex<ReferenceType> {

    private final IndexCell<ReferenceType>    cell;
    private IndexCollection<ReferenceType>    collections;
    private final IODispatcher<ReferenceType> merger;
    
    public IndexCollectionMigration (
            final File indexPrimaryTextLocation,
            final ReferenceFactory<ReferenceType> factory,
            final ByteOrder wordOrdering,
            final Row payloadrow,
            final int entityCacheMaxSize,
            final long targetFileSize,
            final long maxFileSize,
            final IODispatcher<ReferenceType> merger,
            final Log log) throws IOException {
        super(factory);
        
        this.merger = merger;
        final File celldir = new File(indexPrimaryTextLocation, "RICELL");
        this.cell = new IndexCell<ReferenceType>(
                                celldir,
                                factory,
                                wordOrdering,
                                WordReferenceRow.urlEntryRow,
                                entityCacheMaxSize,
                                targetFileSize,
                                maxFileSize,
                                this.merger);
        final File textindexcache = new File(indexPrimaryTextLocation, "RICACHE");
        if (textindexcache.exists()) {
            // migrate the "index.dhtout.blob" into RICELL directory
            File f = new File(textindexcache, "index.dhtout.blob");
            if (f.exists()) {
                File n = this.cell.newContainerBLOBFile();
                f.renameTo(n);
                this.cell.mountBLOBFile(n);
            }
            f = new File(textindexcache, "index.dhtin.blob");
            if (f.exists()) {
                File n = this.cell.newContainerBLOBFile();
                f.renameTo(n);
                this.cell.mountBLOBFile(n);
            }
            // delete everything else
            String[] l = textindexcache.list();
            for (String s: l) {
                f = new File(textindexcache, s);
                FileUtils.deletedelete(f);
            }
            FileUtils.deletedelete(textindexcache);
        }
        
        // open collections, this is for migration only.
        final File textindexcollections = new File(indexPrimaryTextLocation, "RICOLLECTION");
        if (textindexcollections.exists()) {
            this.collections = new IndexCollection<ReferenceType>(
                        textindexcollections, 
                        "collection",
                        factory,
                        12,
                        Base64Order.enhancedCoder,
                        BufferedIndexCollection.maxCollectionPartition, 
                        WordReferenceRow.urlEntryRow, 
                        false);
            if (this.collections.size() == 0) {
                // delete everything here
                this.collections.close();
                this.collections = null;
                String[] l = textindexcollections.list();
                File f;
                for (String s: l) {
                    f = new File(textindexcollections, s);
                    FileUtils.deletedelete(f);
                }
                FileUtils.deletedelete(textindexcollections);
            }
        } else {
            this.collections = null;
        }
    }

    /* methods for interface Index */
    
    public void add(final ReferenceContainer<ReferenceType> entries) throws IOException {
        assert (entries.row().objectsize == WordReferenceRow.urlEntryRow.objectsize);
 
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(entries.getTermHash());
            if (e != null) {
                e.merge(entries);
                cell.add(e);
            } else {
                cell.add(entries);
            }
        } else {
            cell.add(entries);
        }
    }
    
    public void add(final String wordHash, final ReferenceType entry) throws IOException {
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) {
                e.add(entry);
                cell.add(e);
            } else {
                cell.add(wordHash, entry);
            }
        } else {
            cell.add(wordHash, entry);
        }
    }

    public boolean has(final String wordHash) {
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) {
                try {
                    cell.add(e);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return true;
            } else {
                return cell.has(wordHash);
            }
        } else {
            return cell.has(wordHash);
        }
    }
    
    public int count(String wordHash) {
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) {
                try {
                    cell.add(e);
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
                return cell.count(wordHash);
            } else {
                return cell.count(wordHash);
            }
        } else {
            return cell.count(wordHash);
        }
    }
    
    public ReferenceContainer<ReferenceType> get(final String wordHash, final Set<String> urlselection) throws IOException {
        if (wordHash == null) {
            // wrong input
            return null;
        }
        
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) cell.add(e);
        }
        
        return this.cell.get(wordHash, urlselection);
    }

    public ReferenceContainer<ReferenceType> delete(final String wordHash) throws IOException {
        ReferenceContainer<ReferenceType> cc = cell.delete(wordHash);
        if (cc == null) {
            if (collections == null) return null;
            return collections.delete(wordHash);
        } else {
            if (collections == null) return cc;
            ReferenceContainer<ReferenceType> cd = collections.delete(wordHash);
            if (cd == null) return cc;
            return cc.merge(cd);
        }
    }
    
    public boolean remove(final String wordHash, final String urlHash) throws IOException {
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) cell.add(e);
        }
        return cell.remove(wordHash, urlHash);
    }
    
    public int remove(final String wordHash, final Set<String> urlHashes) throws IOException {
        if (this.collections != null) {
            ReferenceContainer<ReferenceType> e = this.collections.delete(wordHash);
            if (e != null) cell.add(e);
        }
        return cell.remove(wordHash, urlHashes);
    }
    
    public synchronized CloneableIterator<ReferenceContainer<ReferenceType>> references(final String startHash, final boolean rot, final boolean ram) throws IOException {
        final CloneableIterator<ReferenceContainer<ReferenceType>> i = wordContainers(startHash, ram);
        if (rot) {
            return new RotateIterator<ReferenceContainer<ReferenceType>>(i, new String(Base64Order.zero(startHash.length())), cell.size() + ((ram) ? 0 : collections.size()));
        }
        return i;
    }
    
    private synchronized CloneableIterator<ReferenceContainer<ReferenceType>> wordContainers(final String startWordHash, final boolean ram) throws IOException {
        final Order<ReferenceContainer<ReferenceType>> containerOrder = new ReferenceContainerOrder<ReferenceType>(factory, cell.ordering().clone());
        ReferenceContainer<ReferenceType> emptyContainer = ReferenceContainer.emptyContainer(factory, startWordHash, 0);
        containerOrder.rotate(emptyContainer);
        if (ram) {
            return cell.references(startWordHash, true);
        }
        if (collections == null) return cell.references(startWordHash, false);
        return new MergeIterator<ReferenceContainer<ReferenceType>>(
                cell.references(startWordHash, false),
                collections.references(startWordHash, false),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true);
    }
    
    public void clear() {
        try {
            cell.clear();
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        if (collections != null) try {
            collections.clear();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    public void close() {
        cell.close();
        if (collections != null) collections.close();
    }
    
    public int size() {
        return (collections == null) ? cell.size() : java.lang.Math.max(collections.size(), cell.size());
    }
    
    public int minMem() {
        return 1024*1024 /* indexing overhead */ + cell.minMem() + ((collections == null) ? 0 : collections.minMem());
    }

    
    /* 
     * methods for cache management
     */
    
    public int getBufferMaxReferences() {
        return cell.getBufferMaxReferences();
    }

    public long getBufferMinAge() {
        return cell.getBufferMinAge();
    }

    public long getBufferMaxAge() {
        return cell.getBufferMaxAge();
    }
    
    public long getBufferSizeBytes() {
        return cell.getBufferSizeBytes();
    }

    public void setBufferMaxWordCount(final int maxWords) {
        cell.setBufferMaxWordCount(maxWords);
    }

    public int getBackendSize() {
        return (collections == null) ? cell.getBackendSize() : collections.size();
    }
    
    public int getBufferSize() {
        return cell.getBufferSize();
    }

    public ByteOrder ordering() {
        return cell.ordering();
    }
    
    public CloneableIterator<ReferenceContainer<ReferenceType>> references(String startWordHash, boolean rot) {
        final Order<ReferenceContainer<ReferenceType>> containerOrder = new ReferenceContainerOrder<ReferenceType>(factory, this.cell.ordering().clone());
        if (this.collections == null) return this.cell.references(startWordHash, rot);
        //else
        return new MergeIterator<ReferenceContainer<ReferenceType>>(
                this.cell.references(startWordHash, false),
                this.collections.references(startWordHash, false),
                containerOrder,
                ReferenceContainer.containerMergeMethod,
                true);
    }

    public void cleanupBuffer(int time) {
        this.cell.cleanupBuffer(time);
    }
}