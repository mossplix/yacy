// Metadata.java
// (C) 2009 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 03.04.2009 on http://yacy.net
//
// This is a part of YaCy, a peer-to-peer based web search engine
//
// $LastChangedDate: 2009-03-20 16:44:59 +0100 (Fr, 20 Mrz 2009) $
// $LastChangedRevision: 5736 $
// $LastChangedBy: borg-0300 $
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

import java.util.Date;

import de.anomic.crawler.CrawlEntry;
import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.order.Bitfield;
import de.anomic.kelondro.text.Reference;

public interface Metadata {

    
    public Row.Entry toRowEntry();

    public String hash();

    public long ranking();
    
    public Date moddate();

    public Date loaddate();

    public Date freshdate();

    public String referrerHash();

    public String md5();

    public char doctype();

    public String language();

    public int size();

    public Bitfield flags();

    public int wordCount();

    public int llocal();

    public int lother();

    public int limage();

    public int laudio();

    public int lvideo();

    public int lapp();
    
    public String snippet();

    public Reference word();

    public boolean isOlder(final Metadata other);

    public String toString(final String snippet);

    public CrawlEntry toBalancerEntry(final String initiatorHash);
    
    public String toString();

}