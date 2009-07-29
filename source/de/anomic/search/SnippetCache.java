// plasmaSnippetCache.java
// -----------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005
// last major change: 09.10.2006
//
// contributions by Marc Nause [MN]
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

package de.anomic.search;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.anomic.crawler.retrieval.Response;
import de.anomic.document.Condenser;
import de.anomic.document.Parser;
import de.anomic.document.ParserException;
import de.anomic.document.Word;
import de.anomic.document.Document;
import de.anomic.document.parser.html.CharacterCoding;
import de.anomic.document.parser.html.ImageEntry;
import de.anomic.http.client.Client;
import de.anomic.http.client.Cache;
import de.anomic.http.metadata.ResponseHeader;
import de.anomic.kelondro.order.Base64Order;
import de.anomic.kelondro.text.metadataPrototype.URLMetadataRow;
import de.anomic.kelondro.util.ScoreCluster;
import de.anomic.kelondro.util.SetTools;
import de.anomic.yacy.yacySearch;
import de.anomic.yacy.yacyURL;
import de.anomic.yacy.logging.Log;

public class SnippetCache {

    private static final int maxCache = 500;
    
    public static final int SOURCE_CACHE = 0;
    public static final int SOURCE_FILE = 1;
    public static final int SOURCE_WEB = 2;
    public static final int SOURCE_METADATA = 3;
    
    public static final int ERROR_NO_HASH_GIVEN = 11;
    public static final int ERROR_SOURCE_LOADING = 12;
    public static final int ERROR_RESOURCE_LOADING = 13;
    public static final int ERROR_PARSER_FAILED = 14;
    public static final int ERROR_PARSER_NO_LINES = 15;
    public static final int ERROR_NO_MATCH = 16;
    
    private static int                           snippetsScoreCounter = 0;
    private static ScoreCluster<String> snippetsScore = null;
    private static final HashMap<String, String> snippetsCache = new HashMap<String, String>();
    
    /**
     * a cache holding URLs to favicons specified by the page content, e.g. by using the html link-tag. e.g.
     * <pre>
     * 	 &lt;link rel="shortcut icon" type="image/x-icon" href="../src/favicon.ico"&gt;
     * </pre>
     */
    private static final HashMap<String, yacyURL> faviconCache = new HashMap<String, yacyURL>();
    private static Log             log = null;
    private static Switchboard     sb = null;
    
    public static void init(
            final Log logx,
            final Switchboard switchboard
    ) {
        log = logx;
        sb = switchboard;
        snippetsScoreCounter = 0;
        snippetsScore = new ScoreCluster<String>();
        snippetsCache.clear(); 
        faviconCache.clear();
    }
    
    public static class TextSnippet {
        private final yacyURL url;
        private String line;
        private final String error;
        private final int errorCode;
        TreeSet<byte[]> remaingHashes;
        private final yacyURL favicon;
        
        /**
         * <code>\\A[^\\p{L}\\p{N}].+</code>
         */
        private final static Pattern p1 = Pattern.compile("\\A[^\\p{L}\\p{N}].+");
        /**
         * <code>.+[^\\p{L}\\p{N}]\\Z</code>
         */
        private final static Pattern p2 = Pattern.compile(".+[^\\p{L}\\p{N}]\\Z");
        /**
         * <code>\\A[\\p{L}\\p{N}]+[^\\p{L}\\p{N}].+\\Z</code>
         */
        private final static Pattern p3 = Pattern.compile("\\A[\\p{L}\\p{N}]+[^\\p{L}\\p{N}].+\\Z");
        /**
         * <code>[^\\p{L}\\p{N}]</code>
         */
        private final static Pattern p4 = Pattern.compile("[^\\p{L}\\p{N}]");
        /**
         * <code>(.*?)(\\&lt;b\\&gt;.+?\\&lt;/b\\&gt;)(.*)</code>
         */
		private final static Pattern p01 = Pattern.compile("(.*?)(\\<b\\>.+?\\</b\\>)(.*)"); // marked words are in <b>-tags
        
        public TextSnippet(final yacyURL url, final String line, final int errorCode, final TreeSet<byte[]> remaingHashes, final String errortext) {
        	this(url,line,errorCode,remaingHashes,errortext,null);
        }
        
        public TextSnippet(final yacyURL url, final String line, final int errorCode, final TreeSet<byte[]> remaingHashes, final String errortext, final yacyURL favicon) {
            this.url = url;
            this.line = line;
            this.errorCode = errorCode;
            this.error = errortext;
            this.remaingHashes = remaingHashes;
            this.favicon = favicon;
        }
        public yacyURL getUrl() {
            return this.url;
        }
        public yacyURL getFavicon() {
        	return this.favicon;
        }
        public boolean exists() {
            return line != null;
        }
        public String toString() {
            return (line == null) ? "" : line;
        }
        public String getLineRaw() {
            return (line == null) ? "" : line;
        }
        public String getError() {
            return (error == null) ? "" : error.trim();
        }
        public int getErrorCode() {
            return errorCode;
        }
        public TreeSet<byte[]> getRemainingHashes() {
            return this.remaingHashes;
        }
        public String getLineMarked(final TreeSet<byte[]> queryHashes) {
            if (line == null) return "";
            if ((queryHashes == null) || (queryHashes.size() == 0)) return line.trim();
            if (line.endsWith(".")) line = line.substring(0, line.length() - 1);
            final Iterator<byte[]> i = queryHashes.iterator();
            byte[] h;
            final String[] w = line.split(" ");
            while (i.hasNext()) {
                h = i.next();
                for (int j = 0; j < w.length; j++) {
            		final ArrayList<String> al = markedWordArrayList(w[j]); // mark special character separated words correctly if more than 1 word has to be marked
            		w[j] = "";
            		for (int k = 0; k < al.size(); k++) {
            			if(k % 2 == 0){ // word has not been marked
            				w[j] += getWordMarked(al.get(k), h);
            			} else { // word has been marked, do not encode again
            				w[j] += al.get(k);
            			}
            		}
                }
            }
            final StringBuilder l = new StringBuilder(line.length() + queryHashes.size() * 8);
            for (int j = 0; j < w.length; j++) {
                l.append(w[j]);
                l.append(' ');
            }
            return l.toString().trim();
        }

        /**
         * mark words with &lt;b&gt;-tags
         * @param word the word to mark
         * @param h the hash of the word to mark
         * @return the marked word if hash matches, else the unmarked word
         * @see #getLineMarked(Set)
         */
        private static String getWordMarked(String word, byte[] h){
            //ignore punctuation marks (contrib [MN])
            //note to myself:
            //For details on regex see "Mastering regular expressions" by J.E.F. Friedl
            //especially p. 123 and p. 390/391 (in the German version of the 2nd edition)

            String prefix = "";
            String postfix = "";
            int len = 0;

            // cut off prefix if it contains of non-characters or non-numbers
            while(p1.matcher(word).find()) {
                prefix = prefix + word.substring(0,1);
                word = word.substring(1);
            }

            // cut off postfix if it contains of non-characters or non-numbers
            while(p2.matcher(word).find()) {
                len = word.length();
                postfix = word.substring(len-1,len) + postfix;
                word = word.substring(0,len-1);
            }

            //special treatment if there is a special character in the word
            if(p3.matcher(word).find()) {
                String out = "";
                String temp = "";
                for(int k=0; k < word.length(); k++) {
                    //is character a special character?
                    if(p4.matcher(word.substring(k,k+1)).find()) {
                        if (new String(Word.word2hash(temp)).equals(new String(h))) temp = "<b>" + CharacterCoding.unicode2html(temp, false) + "</b>";
                        out = out + temp + CharacterCoding.unicode2html(word.substring(k,k+1), false);
                        temp = "";
                    }
                    //last character
                    else if(k == (word.length()-1)) {
                        temp = temp + word.substring(k,k+1);
                        if (new String(Word.word2hash(temp)).equals(new String(h))) temp = "<b>" + CharacterCoding.unicode2html(temp, false) + "</b>";
                        out = out + temp;
                        temp = "";
                    }
                    else temp = temp + word.substring(k,k+1);
                }
                word = out;
            }

            //end contrib [MN]
            else if (new String(Word.word2hash(word)).equals(new String(h))) word = "<b>" + CharacterCoding.unicode2html(word, false) + "</b>";

            word = CharacterCoding.unicode2html(prefix, false)
            	+ word
            	+ CharacterCoding.unicode2html(postfix, false);
            return word;
        }
        
    	/**
    	 * words that already has been marked has index <code>(i % 2 == 1)</code>
    	 * words that has not yet been marked has index <code>(i % 2 == 0)</code>
    	 * @param string the String to be processed
    	 * @return words that already has and has not yet been marked
    	 * @author [DW], 08.11.2008
    	 */
    	private static ArrayList<String> markedWordArrayList(String string){
    	    ArrayList<String> al = new java.util.ArrayList<String>(1);
    		Matcher m = p01.matcher(string);
    		while (m.find()) {
    			al.add(m.group(1));
    			al.add(m.group(2));
    			string = m.group(3); // the postfix
    			m = p01.matcher(string);
    			}
    		al.add(string);
    		return al;
    	}

    }
    
    public static class MediaSnippet {
        public int type;
        public yacyURL href, source;
        public String name, attr;
        public int ranking;
        public MediaSnippet(final int type, final yacyURL href, final String name, final String attr, final int ranking, final yacyURL source) {
            this.type = type;
            this.href = href;
            this.source = source; // the web page where the media resource appeared
            this.name = name;
            this.attr = attr;
            this.ranking = ranking; // the smaller the better! small values should be shown first
            if ((this.name == null) || (this.name.length() == 0)) this.name = "_";
            if ((this.attr == null) || (this.attr.length() == 0)) this.attr = "_";
        }
        public int hashCode() {
            return href.hashCode();
        }
    }
    
    public static boolean existsInCache(final yacyURL url, final TreeSet<byte[]> queryhashes) {
        final String hashes = yacySearch.set2string(queryhashes);
        return retrieveFromCache(hashes, url.hash()) != null;
    }
    
    @SuppressWarnings("unchecked")
    public static TextSnippet retrieveTextSnippet(final URLMetadataRow.Components comp, final TreeSet<byte[]> queryhashes, final boolean fetchOnline, final boolean pre, final int snippetMaxLength, final int maxDocLen, final boolean reindexing) {
        // heise = "0OQUNU3JSs05"
        final yacyURL url = comp.url();
        if (queryhashes.size() == 0) {
            //System.out.println("found no queryhashes for URL retrieve " + url);
            return new TextSnippet(url, null, ERROR_NO_HASH_GIVEN, queryhashes, "no query hashes given");
        }
        
        // try to get snippet from snippetCache
        int source = SOURCE_CACHE;
        final String wordhashes = yacySearch.set2string(queryhashes);
        String line = retrieveFromCache(wordhashes, url.hash());
        if (line != null) {
            // found the snippet
            return new TextSnippet(url, line, source, null, null, faviconCache.get(url.hash()));
        }
        
        /* ===========================================================================
         * LOADING RESOURCE DATA
         * =========================================================================== */
        // if the snippet is not in the cache, we can try to get it from the htcache
        long resContentLength = 0;
        InputStream resContent = null;
        ResponseHeader responseHeader = null;
        try {
            // first try to get the snippet from metadata
            String loc;
            if (containsAllHashes(loc = comp.dc_title(), queryhashes)) {
                // try to create the snippet from information given in the url itself
                return new TextSnippet(url, loc, SOURCE_METADATA, null, null, faviconCache.get(url.hash()));
            } else if (containsAllHashes(loc = comp.dc_creator(), queryhashes)) {
                // try to create the snippet from information given in the creator metadata
                return new TextSnippet(url, loc, SOURCE_METADATA, null, null, faviconCache.get(url.hash()));
            } else if (containsAllHashes(loc = comp.dc_subject(), queryhashes)) {
                // try to create the snippet from information given in the subject metadata
                return new TextSnippet(url, loc, SOURCE_METADATA, null, null, faviconCache.get(url.hash()));
            } else if (containsAllHashes(loc = comp.url().toNormalform(true, true).replace('-', ' '), queryhashes)) {
                // try to create the snippet from information given in the subject metadata
                return new TextSnippet(url, loc, SOURCE_METADATA, null, null, faviconCache.get(url.hash()));
            } else {
                // trying to load the resource from the cache
                resContent = Cache.getContentStream(url);
                responseHeader = Cache.getResponseHeader(url);
                if (resContent != null && ((resContentLength = Cache.getResourceContentLength(url)) > maxDocLen) && (!fetchOnline)) {
                    // content may be too large to be parsed here. To be fast, we omit calculation of snippet here
                    return new TextSnippet(url, null, ERROR_SOURCE_LOADING, queryhashes, "resource available, but too large: " + resContentLength + " bytes");
                } else if (fetchOnline) {
                    // if not found try to download it
                    
                    // download resource using the crawler and keep resource in memory if possible
                    final Response entry = Switchboard.getSwitchboard().loader.load(url, true, reindexing);
                    
                    // getting resource metadata (e.g. the http headers for http resources)
                    if (entry != null) {
                        // place entry on indexing queue
                        sb.toIndexer(entry);
                        
                        // read resource body (if it is there)
                        final byte []resourceArray = entry.getContent();
                        if (resourceArray != null) {
                            resContent = new ByteArrayInputStream(resourceArray);
                            resContentLength = resourceArray.length;
                        } else {
                            resContent = Cache.getContentStream(url); 
                            resContentLength = Cache.getResourceContentLength(url);
                        }
                    }
                    
                    // if it is still not available, report an error
                    if (resContent == null) return new TextSnippet(url, null, ERROR_RESOURCE_LOADING, queryhashes, "error loading resource, plasmaHTCache.Entry cache is NULL");                
                    
                    source = SOURCE_WEB;
                } else {
                    return new TextSnippet(url, null, ERROR_SOURCE_LOADING, queryhashes, "no resource available");
                }
            }
        } catch (final Exception e) {
            //e.printStackTrace();
            return new TextSnippet(url, null, ERROR_SOURCE_LOADING, queryhashes, "error loading resource: " + e.getMessage());
        } 
        
        /* ===========================================================================
         * PARSING RESOURCE
         * =========================================================================== */
        Document document = null;
        try {
             document = parseDocument(url, resContentLength, resContent, responseHeader);
        } catch (final ParserException e) {
            return new TextSnippet(url, null, ERROR_PARSER_FAILED, queryhashes, e.getMessage()); // cannot be parsed
        } finally {
            try { resContent.close(); } catch (final Exception e) {/* ignore this */}
        }
        if (document == null) return new TextSnippet(url, null, ERROR_PARSER_FAILED, queryhashes, "parser error/failed"); // cannot be parsed
        
        
        /* ===========================================================================
         * COMPUTE SNIPPET
         * =========================================================================== */    
        final yacyURL resFavicon = document.getFavicon();
        if (resFavicon != null) faviconCache.put(url.hash(), resFavicon);
        // we have found a parseable non-empty file: use the lines

        // compute snippet from text
        final Iterator<StringBuilder> sentences = document.getSentences(pre);
        if (sentences == null) return new TextSnippet(url, null, ERROR_PARSER_NO_LINES, queryhashes, "parser returned no sentences",resFavicon);
        final Object[] tsr = computeTextSnippet(sentences, queryhashes, snippetMaxLength);
        final String textline = (tsr == null) ? null : (String) tsr[0];
        final TreeSet<byte[]> remainingHashes = (tsr == null) ? queryhashes : (TreeSet<byte[]>) tsr[1];
        
        // compute snippet from media
        //String audioline = computeMediaSnippet(document.getAudiolinks(), queryhashes);
        //String videoline = computeMediaSnippet(document.getVideolinks(), queryhashes);
        //String appline = computeMediaSnippet(document.getApplinks(), queryhashes);
        //String hrefline = computeMediaSnippet(document.getAnchors(), queryhashes);
        //String imageline = computeMediaSnippet(document.getAudiolinks(), queryhashes);
        
        line = "";
        //if (audioline != null) line += (line.length() == 0) ? audioline : "<br />" + audioline;
        //if (videoline != null) line += (line.length() == 0) ? videoline : "<br />" + videoline;
        //if (appline   != null) line += (line.length() == 0) ? appline   : "<br />" + appline;
        //if (hrefline  != null) line += (line.length() == 0) ? hrefline  : "<br />" + hrefline;
        if (textline  != null) line += (line.length() == 0) ? textline  : "<br />" + textline;
        
        if ((line == null) || (remainingHashes.size() > 0)) return new TextSnippet(url, null, ERROR_NO_MATCH, remainingHashes, "no matching snippet found",resFavicon);
        if (line.length() > snippetMaxLength) line = line.substring(0, snippetMaxLength);

        // finally store this snippet in our own cache
        storeToCache(wordhashes, url.hash(), line);
        
        document.close();
        return new TextSnippet(url, line, source, null, null, resFavicon);
    }

    /**
     * Tries to load and parse a resource specified by it's URL.
     * If the resource is not stored in cache and if fetchOnline is set the
     * this function tries to download the resource from web.
     * 
     * @param url the URL of the resource
     * @param fetchOnline specifies if the resource should be loaded from web if it'as not available in the cache
     * @param timeout 
     * @param forText 
     * @param global the domain of the search. If global == true then the content is re-indexed
     * @return the parsed document as {@link Document}
     */
    public static Document retrieveDocument(final yacyURL url, final boolean fetchOnline, final int timeout, final boolean forText, final boolean global) {

        // load resource
        long resContentLength = 0;
        InputStream resContent = null;
        ResponseHeader responseHeader = null;
        try {
            // trying to load the resource from the cache
            resContent = Cache.getContentStream(url);
            responseHeader = Cache.getResponseHeader(url);
            if (resContent != null) {
                // if the content was found
                resContentLength = Cache.getResourceContentLength(url);
            } else if (fetchOnline) {
                // if not found try to download it
                
                // download resource using the crawler and keep resource in memory if possible
                final Response entry = Switchboard.getSwitchboard().loader.load(url, forText, global);
                
                // getting resource metadata (e.g. the http headers for http resources)
                if (entry != null) {

                    // read resource body (if it is there)
                    final byte[] resourceArray = entry.getContent();
                    if (resourceArray != null) {
                        resContent = new ByteArrayInputStream(resourceArray);
                        resContentLength = resourceArray.length;
                    } else {
                        resContent = Cache.getContentStream(url); 
                        resContentLength = Cache.getResourceContentLength(url);
                    }
                }
                
                // if it is still not available, report an error
                if (resContent == null) {
                    Log.logFine("snippet fetch", "plasmaHTCache.Entry cache is NULL for url " + url);
                    return null;
                }
            } else {
                Log.logFine("snippet fetch", "no resource available for url " + url);
                return null;
            }
        } catch (final Exception e) {
            Log.logFine("snippet fetch", "error loading resource: " + e.getMessage() + " for url " + url);
            return null;
        } 

        // parse resource
        Document document = null;
        try {
            document = parseDocument(url, resContentLength, resContent, responseHeader);            
        } catch (final ParserException e) {
            Log.logFine("snippet fetch", "parser error " + e.getMessage() + " for url " + url);
            return null;
        } finally {
            try { resContent.close(); } catch (final Exception e) {}
        }
        return document;
    }

    
    public static void storeToCache(final String wordhashes, final String urlhash, final String snippet) {
        // generate key
        String key = urlhash + wordhashes;

        // do nothing if snippet is known
        if (snippetsCache.containsKey(key)) return;

        // learn new snippet
        snippetsScore.addScore(key, snippetsScoreCounter++);
        snippetsCache.put(key, snippet);

        // care for counter
        if (snippetsScoreCounter == java.lang.Integer.MAX_VALUE) {
            snippetsScoreCounter = 0;
            snippetsScore = new ScoreCluster<String>();
            snippetsCache.clear();
        }
        
        // flush cache if cache is full
        while (snippetsCache.size() > maxCache) {
            key = snippetsScore.getMinObject();
            snippetsScore.deleteScore(key);
            snippetsCache.remove(key);
        }
    }
    
    private static String retrieveFromCache(final String wordhashes, final String urlhash) {
        // generate key
        final String key = urlhash + wordhashes;
        return snippetsCache.get(key);
    }
    
    /*
    private static String computeMediaSnippet(Map<yacyURL, String> media, Set<String> queryhashes) {
        Iterator<Map.Entry<yacyURL, String>> i = media.entrySet().iterator();
        Map.Entry<yacyURL, String> entry;
        yacyURL url;
        String desc;
        Set<String> s;
        String result = "";
        while (i.hasNext()) {
            entry = i.next();
            url = entry.getKey();
            desc = entry.getValue();
            s = removeAppearanceHashes(url.toNormalform(false, false), queryhashes);
            if (s.size() == 0) {
                result += "<br /><a href=\"" + url + "\">" + ((desc.length() == 0) ? url : desc) + "</a>";
                continue;
            }
            s = removeAppearanceHashes(desc, s);
            if (s.size() == 0) {
                result += "<br /><a href=\"" + url + "\">" + ((desc.length() == 0) ? url : desc) + "</a>";
                continue;
            }
        }
        if (result.length() == 0) return null;
        return result.substring(6);
    }
    */
    
    @SuppressWarnings("unchecked")
    private static Object[] /*{String - the snippet, Set - remaining hashes}*/
            computeTextSnippet(final Iterator<StringBuilder> sentences, final TreeSet<byte[]> queryhashes, int maxLength) {
        try {
            if (sentences == null) return null;
            if ((queryhashes == null) || (queryhashes.size() == 0)) return null;
            Iterator<byte[]> j;
            TreeMap<byte[], Integer> hs;
            StringBuilder sentence;
            final TreeMap<Integer, StringBuilder> os = new TreeMap<Integer, StringBuilder>();
            int uniqCounter = 9999;
            int score;
            while (sentences.hasNext()) {
                sentence = sentences.next();
                hs = hashSentence(sentence.toString());
                j = queryhashes.iterator();
                score = 0;
                while (j.hasNext()) {if (hs.containsKey(j.next())) score++;}
                if (score > 0) {
                    os.put(Integer.valueOf(1000000 * score - sentence.length() * 10000 + uniqCounter--), sentence);
                }
            }
            
            String result;
            TreeSet<byte[]> remaininghashes;
            while (os.size() > 0) {
                sentence = os.remove(os.lastKey()); // sentence with the biggest score
                Object[] tsr = computeTextSnippet(sentence.toString(), queryhashes, maxLength);
                if (tsr == null) continue;
                result = (String) tsr[0];
                if ((result != null) && (result.length() > 0)) {
                    remaininghashes = (TreeSet<byte[]>) tsr[1];
                    if (remaininghashes.size() == 0) {
                        // we have found the snippet
                        return new Object[]{result, remaininghashes};
                    } else if (remaininghashes.size() < queryhashes.size()) {
                        // the result has not all words in it.
                        // find another sentence that represents the missing other words
                        // and find recursively more sentences
                        maxLength = maxLength - result.length();
                        if (maxLength < 20) maxLength = 20;
                        tsr = computeTextSnippet(os.values().iterator(), remaininghashes, maxLength);
                        if (tsr == null) return null;
                        final String nextSnippet = (String) tsr[0];
                        if (nextSnippet == null) return tsr;
                        return new Object[]{result + (" / " + nextSnippet), tsr[1]};
                    } else {
                        // error
                        //assert remaininghashes.size() < queryhashes.size() : "remaininghashes.size() = " + remaininghashes.size() + ", queryhashes.size() = " + queryhashes.size() + ", sentence = '" + sentence + "', result = '" + result + "'";
                        continue;
                    }
                }
            }
            return null;
        } catch (final IndexOutOfBoundsException e) {
            log.logSevere("computeSnippet: error with string generation", e);
            return new Object[]{null, queryhashes};
        }
    }
    
    private static Object[] /*{String - the snippet, Set - remaining hashes}*/
            computeTextSnippet(String sentence, final TreeSet<byte[]> queryhashes, final int maxLength) {
        try {
            if (sentence == null) return null;
            if ((queryhashes == null) || (queryhashes.size() == 0)) return null;
            byte[] hash;
            
            // find all hashes that appear in the sentence
            final TreeMap<byte[], Integer> hs = hashSentence(sentence);
            final Iterator<byte[]> j = queryhashes.iterator();
            Integer pos;
            int p, minpos = sentence.length(), maxpos = -1;
            final TreeSet<byte[]> remainingHashes = new TreeSet<byte[]>(Base64Order.enhancedCoder);
            while (j.hasNext()) {
                hash = j.next();
                pos = hs.get(hash);
                if (pos == null) {
                    remainingHashes.add(hash);
                } else {
                    p = pos.intValue();
                    if (p > maxpos) maxpos = p;
                    if (p < minpos) minpos = p;
                }
            }
            // check result size
            maxpos = maxpos + 10;
            if (maxpos > sentence.length()) maxpos = sentence.length();
            if (minpos < 0) minpos = 0;
            // we have a result, but is it short enough?
            if (maxpos - minpos + 10 > maxLength) {
                // the string is too long, even if we cut at both ends
                // so cut here in the middle of the string
                final int lenb = sentence.length();
                sentence = sentence.substring(0, (minpos + 20 > sentence.length()) ? sentence.length() : minpos + 20).trim() +
                " [..] " +
                sentence.substring((maxpos + 26 > sentence.length()) ? sentence.length() : maxpos + 26).trim();
                maxpos = maxpos + lenb - sentence.length() + 6;
            }
            if (maxpos > maxLength) {
                // the string is too long, even if we cut it at the end
                // so cut it here at both ends at once
                assert maxpos >= minpos;
                final int newlen = Math.max(10, maxpos - minpos + 10);
                final int around = (maxLength - newlen) / 2;
                assert minpos - around < sentence.length() : "maxpos = " + maxpos + ", minpos = " + minpos + ", around = " + around + ", sentence.length() = " + sentence.length();
                //assert ((maxpos + around) <= sentence.length()) && ((maxpos + around) <= sentence.length()) : "maxpos = " + maxpos + ", minpos = " + minpos + ", around = " + around + ", sentence.length() = " + sentence.length();
                sentence = "[..] " + sentence.substring(minpos - around, ((maxpos + around) > sentence.length()) ? sentence.length() : (maxpos + around)).trim() + " [..]";
                minpos = around;
                maxpos = sentence.length() - around - 5;
            }
            if (sentence.length() > maxLength) {
                // trim sentence, 1st step (cut at right side)
                sentence = sentence.substring(0, maxpos).trim() + " [..]";
            }
            if (sentence.length() > maxLength) {
                // trim sentence, 2nd step (cut at left side)
                sentence = "[..] " + sentence.substring(minpos).trim();
            }
            if (sentence.length() > maxLength) {
                // trim sentence, 3rd step (cut in the middle)
                sentence = sentence.substring(6, 20).trim() + " [..] " + sentence.substring(sentence.length() - 26, sentence.length() - 6).trim();
            }
            return new Object[] {sentence, remainingHashes};
        } catch (final IndexOutOfBoundsException e) {
            log.logSevere("computeSnippet: error with string generation", e);
            return null;
        }
    }
    
    public static ArrayList<MediaSnippet> retrieveMediaSnippets(final yacyURL url, final TreeSet<byte[]> queryhashes, final int mediatype, final boolean fetchOnline, final int timeout, final boolean reindexing) {
        if (queryhashes.size() == 0) {
            Log.logFine("snippet fetch", "no query hashes given for url " + url);
            return new ArrayList<MediaSnippet>();
        }
        
        final Document document = retrieveDocument(url, fetchOnline, timeout, false, reindexing);
        final ArrayList<MediaSnippet> a = new ArrayList<MediaSnippet>();
        if (document != null) {
            if ((mediatype == QueryParams.CONTENTDOM_ALL) || (mediatype == QueryParams.CONTENTDOM_AUDIO)) a.addAll(computeMediaSnippets(document, queryhashes, QueryParams.CONTENTDOM_AUDIO));
            if ((mediatype == QueryParams.CONTENTDOM_ALL) || (mediatype == QueryParams.CONTENTDOM_VIDEO)) a.addAll(computeMediaSnippets(document, queryhashes, QueryParams.CONTENTDOM_VIDEO));
            if ((mediatype == QueryParams.CONTENTDOM_ALL) || (mediatype == QueryParams.CONTENTDOM_APP)) a.addAll(computeMediaSnippets(document, queryhashes, QueryParams.CONTENTDOM_APP));
            if ((mediatype == QueryParams.CONTENTDOM_ALL) || (mediatype == QueryParams.CONTENTDOM_IMAGE)) a.addAll(computeImageSnippets(document, queryhashes));
        }
        return a;
    }
    
    public static ArrayList<MediaSnippet> computeMediaSnippets(final Document document, final TreeSet<byte[]> queryhashes, final int mediatype) {
        
        if (document == null) return new ArrayList<MediaSnippet>();
        Map<yacyURL, String> media = null;
        if (mediatype == QueryParams.CONTENTDOM_AUDIO) media = document.getAudiolinks();
        else if (mediatype == QueryParams.CONTENTDOM_VIDEO) media = document.getVideolinks();
        else if (mediatype == QueryParams.CONTENTDOM_APP) media = document.getApplinks();
        if (media == null) return null;
        
        final Iterator<Map.Entry<yacyURL, String>> i = media.entrySet().iterator();
        Map.Entry<yacyURL, String> entry;
        yacyURL url;
        String desc;
        TreeSet<byte[]> s;
        final ArrayList<MediaSnippet> result = new ArrayList<MediaSnippet>();
        while (i.hasNext()) {
            entry = i.next();
            url = entry.getKey();
            desc = entry.getValue();
            s = removeAppearanceHashes(url.toNormalform(false, false), queryhashes);
            if (s.size() == 0) {
                result.add(new MediaSnippet(mediatype, url, desc, null, 0, document.dc_source()));
                continue;
            }
            s = removeAppearanceHashes(desc, s);
            if (s.size() == 0) {
                result.add(new MediaSnippet(mediatype, url, desc, null, 0, document.dc_source()));
                continue;
            }
        }
        return result;
    }
    
    public static ArrayList<MediaSnippet> computeImageSnippets(final Document document, final TreeSet<byte[]> queryhashes) {
        
        final TreeSet<ImageEntry> images = new TreeSet<ImageEntry>();
        images.addAll(document.getImages().values()); // iterates images in descending size order!
        // a measurement for the size of the images can be retrieved using the htmlFilterImageEntry.hashCode()
        
        final Iterator<ImageEntry> i = images.iterator();
        ImageEntry ientry;
        yacyURL url;
        String desc;
        TreeSet<byte[]> s;
        final ArrayList<MediaSnippet> result = new ArrayList<MediaSnippet>();
        while (i.hasNext()) {
            ientry = i.next();
            url = ientry.url();
            desc = ientry.alt();
            s = removeAppearanceHashes(url.toNormalform(false, false), queryhashes);
            if (s.size() == 0) {
                final int ranking = ientry.hashCode();
                result.add(new MediaSnippet(QueryParams.CONTENTDOM_IMAGE, url, desc, ientry.width() + " x " + ientry.height(), ranking, document.dc_source()));
                continue;
            }
            s = removeAppearanceHashes(desc, s);
            if (s.size() == 0) {
                final int ranking = ientry.hashCode();
                result.add(new MediaSnippet(QueryParams.CONTENTDOM_IMAGE, url, desc, ientry.width() + " x " + ientry.height(), ranking, document.dc_source()));
                continue;
            }
        }
        return result;
    }
    
    private static TreeSet<byte[]> removeAppearanceHashes(final String sentence, final TreeSet<byte[]> queryhashes) {
        // remove all hashes that appear in the sentence
        if (sentence == null) return queryhashes;
        final TreeMap<byte[], Integer> hs = hashSentence(sentence);
        final Iterator<byte[]> j = queryhashes.iterator();
        byte[] hash;
        Integer pos;
        final TreeSet<byte[]> remaininghashes = new TreeSet<byte[]>(Base64Order.enhancedCoder);
        while (j.hasNext()) {
            hash = j.next();
            pos = hs.get(hash);
            if (pos == null) {
                remaininghashes.add(hash);
            }
        }
        return remaininghashes;
    }
    
    private static TreeMap<byte[], Integer> hashSentence(final String sentence) {
        // generates a word-wordPos mapping
        final TreeMap<byte[], Integer> map = new TreeMap<byte[], Integer>(Base64Order.enhancedCoder);
        final Enumeration<StringBuilder> words = Condenser.wordTokenizer(sentence, "UTF-8");
        int pos = 0;
        StringBuilder word;
        byte[] hash;
        while (words.hasMoreElements()) {
            word = words.nextElement();
            hash = Word.word2hash(new String(word));
            if (!map.containsKey(hash)) map.put(hash, Integer.valueOf(pos)); // don't overwrite old values, that leads to too far word distances
            pos += word.length() + 1;
        }
        return map;
    }
    
    private static boolean containsAllHashes(final String sentence, final Set<byte[]> queryhashes) {
        final TreeMap<byte[], Integer> m = hashSentence(sentence);
        final Iterator<byte[]> i = queryhashes.iterator();
        while (i.hasNext()) {
            if (!(m.containsKey(i.next()))) return false;
        }
        return true;
    }
    
    public static Document parseDocument(final yacyURL url, final long contentLength, final InputStream resourceStream) throws ParserException {
        return parseDocument(url, contentLength, resourceStream, null);
    }
    
    /**
     * Parse the resource
     * @param url the URL of the resource
     * @param contentLength the contentLength of the resource
     * @param resourceStream the resource body as stream
     * @param docInfo metadata about the resource
     * @return the extracted data
     * @throws ParserException
     */
    public static Document parseDocument(final yacyURL url, final long contentLength, final InputStream resourceStream, ResponseHeader responseHeader) throws ParserException {
        try {
            if (resourceStream == null) return null;

            // STEP 1: if no resource metadata is available, try to load it from cache 
            if (responseHeader == null) {
                // try to get the header from the htcache directory
                try {                    
                    responseHeader = Cache.getResponseHeader(url);
                } catch (final Exception e) {
                    // ignore this. resource info loading failed
                }   
            }
            
            // STEP 2: if the metadata is still null try to download it from web
            if ((responseHeader == null) && (url.getProtocol().startsWith("http"))) {
                // TODO: we need a better solution here
                // e.g. encapsulate this in the crawlLoader class
                
                // getting URL mimeType
                try {
                    responseHeader = Client.whead(url.toString());
                } catch (final Exception e) {
                    // ingore this. http header download failed
                } 
            }

            // STEP 3: if the metadata is still null try to guess the mimeType of the resource
            String supportError = Parser.supports(url, responseHeader == null ? null : responseHeader.mime());
            if (supportError != null) {
                log.logInfo("could not generate snippet for " + url.toNormalform(true, false) + ": " + supportError);
                return null;
            }
            if (responseHeader == null) {
                return Parser.parseSource(url, null, null, contentLength, resourceStream);
            }
            return Parser.parseSource(url, responseHeader.mime(), responseHeader.getCharacterEncoding(), contentLength, resourceStream);
        } catch (final InterruptedException e) {
            // interruption of thread detected
            return null;
        }
    }
    
    /**
     * 
     * @param url
     * @param fetchOnline
     * @param socketTimeout
     * @param forText 
     * @return an Object array containing
     * <table>
     * <tr><td>[0]</td><td>the content as {@link InputStream}</td></tr>
     * <tr><td>[1]</td><td>the content-length as {@link Integer}</td></tr>
     * </table>
     * @throws IOException 
     */
    public static Object[] getResource(final yacyURL url, final boolean fetchOnline, final int socketTimeout, final boolean forText, final boolean reindexing) throws IOException {
        // load the url as resource from the web
            long contentLength = -1;
            
            // trying to load the resource body from cache
            InputStream resource = Cache.getContentStream(url);
            if (resource != null) {
                contentLength = Cache.getResourceContentLength(url);
            } else if (fetchOnline) {
                // if the content is not available in cache try to download it from web
                
                // try to download the resource using a crawler
                final Response entry = Switchboard.getSwitchboard().loader.load(url, forText, reindexing);
                if (entry == null) return null; // not found in web
                
                // read resource body (if it is there)
                final byte[] resourceArray = entry.getContent();
            
                // in case that the resource was not in ram, read it from disk
                if (resourceArray == null) {
                    resource = Cache.getContentStream(url);   
                    contentLength = Cache.getResourceContentLength(url); 
                } else {
                    resource = new ByteArrayInputStream(resourceArray);
                    contentLength = resourceArray.length;
                }
            } else {
                return null;
            }
            return new Object[]{resource, Long.valueOf(contentLength)};
    }
    
    public static String failConsequences(final TextSnippet snippet, final String eventID) throws IOException {
        // problems with snippet fetch
        final String urlHash = snippet.getUrl().hash();
        final String querystring = SetTools.setToString(snippet.getRemainingHashes(), ' ');
        if ((snippet.getErrorCode() == ERROR_SOURCE_LOADING) ||
            (snippet.getErrorCode() == ERROR_RESOURCE_LOADING) ||
            (snippet.getErrorCode() == ERROR_PARSER_FAILED) ||
            (snippet.getErrorCode() == ERROR_PARSER_NO_LINES)) {
            log.logInfo("error: '" + snippet.getError() + "', remove url = " + snippet.getUrl().toNormalform(false, true) + ", cause: " + snippet.getError());
            Switchboard.getSwitchboard().indexSegment.urlMetadata().remove(urlHash);
            final QueryEvent event = QueryEvent.getEvent(eventID);
            assert Switchboard.getSwitchboard() != null;
            assert Switchboard.getSwitchboard().indexSegment != null;
            assert event != null : "eventID = " + eventID;
            assert event.getQuery() != null;
            Switchboard.getSwitchboard().indexSegment.termIndex().remove(event.getQuery().queryHashes, urlHash);
            event.remove(urlHash);
        }
        if (snippet.getErrorCode() == ERROR_NO_MATCH) {
            log.logInfo("error: '" + snippet.getError() + "', remove words '" + querystring + "' for url = " + snippet.getUrl().toNormalform(false, true) + ", cause: " + snippet.getError());
            Switchboard.getSwitchboard().indexSegment.termIndex().remove(snippet.remaingHashes, urlHash);
            QueryEvent.getEvent(eventID).remove(urlHash);
        }
        return snippet.getError();
    }
    
}