// ReverseIndex.java
// -----------------------------
// (C) 2005 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 6.5.2005 on http://www.anomic.de
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2006-04-02 22:40:07 +0200 (So, 02 Apr 2006) $
// $LastChangedRevision: 1986 $
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

import java.io.IOException;
import java.util.Set;

import de.anomic.kelondro.order.CloneableIterator;

public interface Index {
    
	/**
	 * add references to the reverse index
	 * when no references are stored, the new Entries are simply added,
	 * if there are already references to the word that is denoted
	 * with the reference stored, then merge the old and the new reference
	 * @param newEntries the References to be merged with existing references
	 * @throws IOException
	 */
	public void addReferences(ReferenceContainer newEntries) throws IOException;
    
	/**
	 * check if there are references stored to the given word hash
	 * @param wordHash
	 * @return true if references exist, false if not
	 */
	public boolean hasReferences(String wordHash); // should only be used if in case that true is returned the getContainer is NOT called
    
	/**
	 * count the number of references for the given word
	 * do not use this method to check the existence of a reference by comparing
	 * the result with zero, use hasReferences instead.
	 * @param wordHash
	 * @return the number of references to the given word
	 */
	public int countReferences(final String wordHash);
    
	/**
	 * get the references to a given word.
	 *  if referenceselection is not null, then all url references which are not
	 *  in referenceselection are removed from the container
	 * @param wordHash
	 * @param referenceselection
	 * @return the references
	 * @throws IOException
	 */
	public ReferenceContainer getReferences(String wordHash, Set<String> referenceselection) throws IOException;
    
    /**
     * delete all references for a word
     * @param wordHash
     * @return the deleted references
     * @throws IOException
     */
	public ReferenceContainer deleteAllReferences(String wordHash) throws IOException;
    
	/**
	 * remove a specific reference entry
	 * @param wordHash
	 * @param referenceHash the key for the reference entry to be removed
	 * @return
	 * @throws IOException
	 */
    public boolean removeReference(String wordHash, String referenceHash) throws IOException;
    
    /**
     * remove a set of reference entries for a given word
     * @param wordHash the key for the references
     * @param referenceHash the reference entry keys
     * @return
     * @throws IOException
     */
    public int removeReferences(String wordHash, Set<String> referenceHashes) throws IOException;
    
    /**
     * iterate all references from the beginning of a specific word hash
     * @param startWordHash
     * @param rot if true, then rotate at the end to the beginning
     * @param ram
     * @return
     * @throws IOException
     */
	public CloneableIterator<ReferenceContainer> referenceIterator(String startWordHash, boolean rot, boolean ram) throws IOException; // method to replace wordHashes
    
    /**
     * delete all references entries
     * @throws IOException
     */
    public void clear() throws IOException;
    
    /**
     * close the reverse index
     */
    public void close();
    
    /**
     * the number of all references
     * @return the nnumber of all references
     */
    public int size();
    
    /**
     * calculate needed memory
     * @return the memory needed to operate the object
     */
    public int minMem();
    
}