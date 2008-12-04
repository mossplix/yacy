// mediawiki.java
// (C) 2007 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 2007 on http://yacy.net
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

import java.io.File;
import java.io.IOException;

import de.anomic.http.httpRequestHeader;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverObjects;
import de.anomic.server.serverSwitch;
import de.anomic.tools.mediawikiIndex;

public class mediawiki_p {
    
    //http://localhost:8080/mediawiki_p.html?dump=wikipedia.de.xml&title=Kartoffel
    public static serverObjects respond(final httpRequestHeader header, serverObjects post, final serverSwitch<?> env) throws IOException {
        final plasmaSwitchboard sb = (plasmaSwitchboard) env;
        final serverObjects prop = new serverObjects();
        prop.put("title", "");
        prop.put("page", "");
        
        if (post == null) {
            return post;
        }

        String dump = post.get("dump", null);
        String title = post.get("title", null);
        if (dump == null || title == null) return post;
        
        
        File dumpFile = new File(sb.getRootPath(), "DATA/HTCACHE/mediawiki/" + dump);
        if (!dumpFile.exists()) return post;
        mediawikiIndex.checkIndex(dumpFile);
        mediawikiIndex.wikirecord w = mediawikiIndex.find(title.replaceAll(" ", "_"), mediawikiIndex.idxFromWikimediaXML(dumpFile));
        if (w == null) {
            return post;
        }
        String page = new String(mediawikiIndex.read(dumpFile, w.start, (int) (w.end - w.start)), "UTF-8");
        int p = page.indexOf("<text");
        if (p < 0) return prop;
        p = page.indexOf('>', p);
        if (p < 0) return prop;
        p++;
        int q = page.lastIndexOf("</text>");
        if (q < 0) return prop;
        page = page.substring(p, q);
        
        prop.putHTML("title", title);
        prop.putWiki("page", page);
            
        return prop;
    }
}