// kelondroHandle.java
// (C) 2003 - 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2003 on http://yacy.net
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

package de.anomic.kelondro.table;

public class RecordHandle implements Comparable<RecordHandle> {
    
    public final static int NUL = Integer.MIN_VALUE; // the meta value for the kelondroTray' NUL abstraction

    protected int index;

    protected RecordHandle(final int i) {
    	assert i != 1198412402;
        assert (i == NUL) || (i >= 0) : "node handle index too low: " + i;
        //assert (i == NUL) || (i < USAGE.allCount()) : "node handle index too high: " + i + ", USEDC=" + USAGE.USEDC + ", FREEC=" + USAGE.FREEC;
        this.index = i;
        //if ((USAGE != null) && (this.index != NUL)) USAGE.allocate(this.index);
    }

    public boolean isNUL() {
        return index == NUL;
    }

    public String toString() {
        if (index == NUL) return "NULL";
        String s = Integer.toHexString(index);
        while (s.length() < 4) s = "0" + s;
        return s;
    }

    public boolean equals(final RecordHandle h) {
        assert (index != NUL);
        assert (h.index != NUL);
        return (this.index == h.index);
    }

    public boolean equals(final Object h) {
        assert (index != NUL);
        assert (h instanceof RecordHandle && ((RecordHandle) h).index != NUL);
        return (h instanceof RecordHandle && this.index == ((RecordHandle) h).index);
    }

    public int compare(final RecordHandle h0, final RecordHandle h1) {
        assert ((h0).index != NUL);
        assert ((h1).index != NUL);
        if ((h0).index < (h1).index) return -1;
        if ((h0).index > (h1).index) return 1;
        return 0;
    }

    public int compareTo(final RecordHandle h) {
        // this is needed for a TreeMap
        assert (index != NUL) : "this.index is NUL in compareTo";
        assert ((h).index != NUL) : "handle.index is NUL in compareTo";
        if (index < (h).index) return -1;
        if (index > (h).index) return 1;
        return 0;
    }

    public int hashCode() {
        assert (index != NUL);
        return this.index;
    }
}