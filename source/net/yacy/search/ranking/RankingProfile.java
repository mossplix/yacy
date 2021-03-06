// RankingProfile.java
// -------------------------------
// part of YACY
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2006
// Created: 05.02.2006
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
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

package net.yacy.search.ranking;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import net.yacy.kelondro.logging.Log;
import net.yacy.search.snippet.ContentDomain;

public class RankingProfile {

    // pre-sort attributes
    public static final String APPEMPH            = "appemph";
    public static final String APPURL             = "appurl";
    public static final String APP_DC_CREATOR     = "appauthor";  // the author field
    public static final String APP_DC_DESCRIPTION = "appref";     // references to the source (content of <a> tag)
    public static final String APP_DC_SUBJECT     = "apptags";    // tags
    public static final String APP_DC_TITLE       = "appdescr";   // title of page
    public static final String AUTHORITY          = "authority";
    public static final String CATHASAPP          = "cathasapp";
    public static final String CATHASAUDIO        = "cathasaudio";
    public static final String CATHASIMAGE        = "cathasimage";
    public static final String CATHASVIDEO        = "cathasvideo";
    public static final String CATINDEXOF         = "catindexof";
    public static final String DATE               = "date";
    public static final String DOMLENGTH          = "domlength";
    public static final String HITCOUNT           = "hitcount";
    public static final String LANGUAGE           = "language";   // ranking of preferred language
    public static final String LLOCAL             = "llocal";
    public static final String LOTHER             = "lother";
    public static final String PHRASESINTEXT      = "phrasesintext";
    public static final String POSINPHRASE        = "posinphrase";
    public static final String POSINTEXT          = "posintext";
    public static final String POSOFPHRASE        = "posofphrase";
    public static final String TERMFREQUENCY      = "tf";
    public static final String URLCOMPS           = "urlcomps";
    public static final String URLLENGTH          = "urllength";
    public static final String WORDDISTANCE       = "worddistance";
    public static final String WORDSINTEXT        = "wordsintext";
    public static final String WORDSINTITLE       = "wordsintitle";
    public static final String YBR                = "ybr";

    // post-sort predicates
    public static final String URLCOMPINTOPLIST   = "urlcompintoplist";
    public static final String DESCRCOMPINTOPLIST = "descrcompintoplist";
    public static final String PREFER = "prefer";

    // coefficient max/min values
    public static final int COEFF_MIN =  0;
    public static final int COEFF_MAX = 15;

    public int
        coeff_domlength, coeff_ybr, coeff_date, coeff_wordsintitle, coeff_wordsintext, coeff_phrasesintext,
        coeff_llocal, coeff_lother, coeff_urllength, coeff_urlcomps, coeff_hitcount,
        coeff_posintext, coeff_posofphrase, coeff_posinphrase, coeff_authority, coeff_worddistance,
        coeff_appurl, coeff_app_dc_title, coeff_app_dc_creator, coeff_app_dc_subject, coeff_app_dc_description, coeff_appemph,
        coeff_catindexof, coeff_cathasimage, coeff_cathasaudio, coeff_cathasvideo, coeff_cathasapp,
        coeff_urlcompintoplist, coeff_descrcompintoplist, coeff_prefer,
        coeff_termfrequency, coeff_language;

    public RankingProfile(final ContentDomain mediatype) {
        // set default-values
        this.coeff_appemph            = 5;
        this.coeff_appurl             = 11;
        this.coeff_app_dc_creator     = 1;
        this.coeff_app_dc_description = 4;
        this.coeff_app_dc_subject     = 2;
        this.coeff_app_dc_title       = 8;
        this.coeff_authority          = 5;
        this.coeff_cathasapp          = (mediatype == ContentDomain.APP) ? 15 : 0;
        this.coeff_cathasaudio        = (mediatype == ContentDomain.AUDIO) ? 15 : 0;
        this.coeff_cathasimage        = (mediatype == ContentDomain.IMAGE) ? 15 : 0;
        this.coeff_cathasvideo        = (mediatype == ContentDomain.VIDEO) ? 15 : 0;
        this.coeff_catindexof         = (mediatype == ContentDomain.TEXT) ? 0 : 15;
        this.coeff_date               = 7;
        this.coeff_domlength          = 9;
        this.coeff_hitcount           = 1;
        this.coeff_language           = 2;
        this.coeff_llocal             = 0;
        this.coeff_lother             = 7;
        this.coeff_phrasesintext      = 0;
        this.coeff_posinphrase        = 0;
        this.coeff_posintext          = 4;
        this.coeff_posofphrase        = 0;
        this.coeff_termfrequency      = 6;
        this.coeff_urlcomps           = 7;
        this.coeff_urllength          = 6;
        this.coeff_worddistance       = 15;
        this.coeff_wordsintext        = 3;
        this.coeff_wordsintitle       = 2;
        this.coeff_ybr                = 8;

        this.coeff_urlcompintoplist   = 15;
        this.coeff_descrcompintoplist = 15;
        this.coeff_prefer             = 15;
    }

    public RankingProfile(final String prefix, String profile) {
        this(ContentDomain.TEXT); // set defaults
        if ((profile != null) && (profile.length() > 0)) {
            //parse external form
            final Map<String, Integer> coeff = new HashMap<String, Integer>(40);
            final String[] elts;
            if (profile.length() > 0 && profile.charAt(0) == '{' && profile.endsWith("}")) {
                profile = profile.substring(1, profile.length() - 1);
            }
            profile = profile.trim();
            if (profile.indexOf('&') > 0) elts = profile.split("&"); else elts = profile.split(",");
            int p;
            final int s = (prefix == null) ? 0 : prefix.length();
            String e;

            for (final String elt : elts) {
                e = elt.trim();
                if ((s == 0) || (e.startsWith(prefix))) {
                    p = e.indexOf('=');
                    if (p < 0) System.out.println("DEBUG: bug in plasmaSearchRankingProfile: e = " + e);
                    if ((p > 0) && (e.length() > p + 1)) try {
                        coeff.put(e.substring(s, p), Integer.valueOf(Integer.parseInt(e.substring(p + 1))));
                    } catch (final NumberFormatException e1) {
                        System.out.println("wrong parameter: " + e.substring(s, p) + "=" + e.substring(p + 1));
                        Log.logException(e1);
                    }
                }
            }
            this.coeff_domlength          = parseMap(coeff, DOMLENGTH, this.coeff_domlength);
            this.coeff_ybr                = parseMap(coeff, YBR, this.coeff_ybr);
            this.coeff_date               = parseMap(coeff, DATE, this.coeff_date);
            this.coeff_wordsintitle       = parseMap(coeff, WORDSINTITLE, this.coeff_wordsintitle);
            this.coeff_wordsintext        = parseMap(coeff, WORDSINTEXT, this.coeff_wordsintext);
            this.coeff_phrasesintext      = parseMap(coeff, PHRASESINTEXT, this.coeff_phrasesintext);
            this.coeff_llocal             = parseMap(coeff, LLOCAL, this.coeff_llocal);
            this.coeff_lother             = parseMap(coeff, LOTHER, this.coeff_lother);
            this.coeff_urllength          = parseMap(coeff, URLLENGTH, this.coeff_urllength);
            this.coeff_urlcomps           = parseMap(coeff, URLCOMPS, this.coeff_urlcomps);
            this.coeff_hitcount           = parseMap(coeff, HITCOUNT, this.coeff_hitcount);
            this.coeff_posintext          = parseMap(coeff, POSINTEXT, this.coeff_posintext);
            this.coeff_posofphrase        = parseMap(coeff, POSOFPHRASE, this.coeff_posofphrase);
            this.coeff_posinphrase        = parseMap(coeff, POSINPHRASE, this.coeff_posinphrase);
            this.coeff_authority          = parseMap(coeff, AUTHORITY, this.coeff_authority);
            this.coeff_worddistance       = parseMap(coeff, WORDDISTANCE, this.coeff_worddistance);
            this.coeff_appurl             = parseMap(coeff, APPURL, this.coeff_appurl);
            this.coeff_app_dc_title       = parseMap(coeff, APP_DC_TITLE, this.coeff_app_dc_title);
            this.coeff_app_dc_creator     = parseMap(coeff, APP_DC_CREATOR, this.coeff_app_dc_creator);
            this.coeff_app_dc_subject     = parseMap(coeff, APP_DC_SUBJECT, this.coeff_app_dc_subject);
            this.coeff_app_dc_description = parseMap(coeff, APP_DC_DESCRIPTION, this.coeff_app_dc_description);
            this.coeff_appemph            = parseMap(coeff, APPEMPH, this.coeff_appemph);
            this.coeff_catindexof         = parseMap(coeff, CATINDEXOF, this.coeff_catindexof);
            this.coeff_cathasimage        = parseMap(coeff, CATHASIMAGE, this.coeff_cathasimage);
            this.coeff_cathasaudio        = parseMap(coeff, CATHASAUDIO, this.coeff_cathasaudio);
            this.coeff_cathasvideo        = parseMap(coeff, CATHASVIDEO, this.coeff_cathasvideo);
            this.coeff_cathasapp          = parseMap(coeff, CATHASAPP, this.coeff_cathasapp);
            this.coeff_termfrequency      = parseMap(coeff, TERMFREQUENCY, this.coeff_termfrequency);
            this.coeff_urlcompintoplist   = parseMap(coeff, URLCOMPINTOPLIST, this.coeff_urlcompintoplist);
            this.coeff_descrcompintoplist = parseMap(coeff, DESCRCOMPINTOPLIST, this.coeff_descrcompintoplist);
            this.coeff_prefer             = parseMap(coeff, PREFER, this.coeff_prefer);
            this.coeff_language           = parseMap(coeff, LANGUAGE, this.coeff_language);
        }
    }

    private static int parseMap(final Map<String, Integer> coeff, final String attr, final int dflt) {
        if (!coeff.containsKey(attr))
            return dflt;
        return (coeff.get(attr)).intValue();
    }

    public String toExternalString() {
        return toExternalMap("").toString();
    }

    public Map<String, String> toExternalMap(final String prefix) {
    	final Map<String, String> ext = preToExternalMap(prefix);
    	ext.putAll(postToExternalMap(prefix));
    	return ext;
    }

    public Map<String, String> preToExternalMap(final String prefix) {
        final Map<String, String> ext = new LinkedHashMap<String, String>(40);
        ext.put(prefix + APPEMPH, Integer.toString(this.coeff_appemph));
        ext.put(prefix + APPURL, Integer.toString(this.coeff_appurl));
        ext.put(prefix + APP_DC_CREATOR, Integer.toString(this.coeff_app_dc_creator));
        ext.put(prefix + APP_DC_DESCRIPTION, Integer.toString(this.coeff_app_dc_description));
        ext.put(prefix + APP_DC_SUBJECT, Integer.toString(this.coeff_app_dc_subject));
        ext.put(prefix + APP_DC_TITLE, Integer.toString(this.coeff_app_dc_title));
        ext.put(prefix + AUTHORITY, Integer.toString(this.coeff_authority));
        ext.put(prefix + CATHASAPP, Integer.toString(this.coeff_cathasapp));
        ext.put(prefix + CATHASAUDIO, Integer.toString(this.coeff_cathasaudio));
        ext.put(prefix + CATHASIMAGE, Integer.toString(this.coeff_cathasimage));
        ext.put(prefix + CATHASVIDEO, Integer.toString(this.coeff_cathasvideo));
        ext.put(prefix + CATINDEXOF, Integer.toString(this.coeff_catindexof));
        ext.put(prefix + DATE, Integer.toString(this.coeff_date));
        ext.put(prefix + DOMLENGTH, Integer.toString(this.coeff_domlength));
        ext.put(prefix + HITCOUNT, Integer.toString(this.coeff_hitcount));
        ext.put(prefix + LANGUAGE, Integer.toString(this.coeff_language));
        ext.put(prefix + LLOCAL, Integer.toString(this.coeff_llocal));
        ext.put(prefix + LOTHER, Integer.toString(this.coeff_lother));
        ext.put(prefix + PHRASESINTEXT, Integer.toString(this.coeff_phrasesintext));
        ext.put(prefix + POSINPHRASE, Integer.toString(this.coeff_posinphrase));
        ext.put(prefix + POSINTEXT, Integer.toString(this.coeff_posintext));
        ext.put(prefix + POSOFPHRASE, Integer.toString(this.coeff_posofphrase));
        ext.put(prefix + TERMFREQUENCY, Integer.toString(this.coeff_termfrequency));
        ext.put(prefix + URLCOMPS, Integer.toString(this.coeff_urlcomps));
        ext.put(prefix + URLLENGTH, Integer.toString(this.coeff_urllength));
        ext.put(prefix + WORDDISTANCE, Integer.toString(this.coeff_worddistance));
        ext.put(prefix + WORDSINTEXT, Integer.toString(this.coeff_wordsintext));
        ext.put(prefix + WORDSINTITLE, Integer.toString(this.coeff_wordsintitle));
        ext.put(prefix + YBR, Integer.toString(this.coeff_ybr));
        return ext;
    }

    public Map<String, String> postToExternalMap(final String prefix) {
    	final Map<String, String> ext = new LinkedHashMap<String, String>();
        ext.put(prefix + URLCOMPINTOPLIST, Integer.toString(this.coeff_urlcompintoplist));
        ext.put(prefix + DESCRCOMPINTOPLIST, Integer.toString(this.coeff_descrcompintoplist));
        ext.put(prefix + PREFER, Integer.toString(this.coeff_prefer));
        return ext;
    }

    public String toExternalURLGet(final String prefix) {
    	final Map<String, String> emap = toExternalMap("");
        final StringBuilder ext = new StringBuilder(emap.size() * 40);
        for (final Map.Entry<String, String> entry: emap.entrySet()) {
            ext.append('&');
            ext.append(prefix);
            ext.append(entry.getKey());
            ext.append('=');
            ext.append(entry.getValue());
        }
        return ext.toString();
    }

}
