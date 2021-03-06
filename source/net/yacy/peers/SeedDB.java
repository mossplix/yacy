// yacySeedDB.java
// -------------------------------------
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004, 2005
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

package net.yacy.peers;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.ref.SoftReference;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.yacy.cora.document.ASCII;
import net.yacy.cora.document.UTF8;
import net.yacy.cora.protocol.ClientIdentification;
import net.yacy.cora.protocol.Domains;
import net.yacy.cora.protocol.HeaderFramework;
import net.yacy.cora.protocol.RequestHeader;
import net.yacy.cora.protocol.http.HTTPClient;
import net.yacy.kelondro.blob.MapDataMining;
import net.yacy.kelondro.data.meta.DigestURI;
import net.yacy.kelondro.data.word.Word;
import net.yacy.kelondro.index.HandleSet;
import net.yacy.kelondro.index.RowSpaceExceededException;
import net.yacy.kelondro.logging.Log;
import net.yacy.kelondro.order.Base64Order;
import net.yacy.kelondro.util.FileUtils;
import net.yacy.kelondro.util.kelondroException;
import net.yacy.peers.dht.PartitionScheme;
import net.yacy.peers.dht.VerticalWordPartitionScheme;
import net.yacy.peers.operation.yacySeedUploader;
import net.yacy.search.Switchboard;
import de.anomic.http.server.AlternativeDomainNames;
import de.anomic.http.server.HTTPDemon;
import de.anomic.server.serverCore;
import de.anomic.server.serverSwitch;

public final class SeedDB implements AlternativeDomainNames {

    // global statics

    private static final int dhtActivityMagic = 32;

    /**
     * <p><code>public static final String <strong>DBFILE_OWN_SEED</strong> = "mySeed.txt"</code></p>
     * <p>Name of the file containing the database holding this peer's seed</p>
     */
    public static final String DBFILE_OWN_SEED = "mySeed.txt";

    public static final String[]      sortFields = new String[] {Seed.LCOUNT, Seed.RCOUNT, Seed.ICOUNT, Seed.UPTIME, Seed.VERSION, Seed.LASTSEEN};
    public static final String[]   longaccFields = new String[] {Seed.LCOUNT, Seed.ICOUNT, Seed.ISPEED};
    public static final String[] doubleaccFields = new String[] {Seed.RSPEED};

    // class objects
    private File seedActiveDBFile, seedPassiveDBFile, seedPotentialDBFile;
    private File myOwnSeedFile;
    private MapDataMining seedActiveDB, seedPassiveDB, seedPotentialDB;

    protected int lastSeedUpload_seedDBSize = 0;
    public long lastSeedUpload_timeStamp = System.currentTimeMillis();
    protected String lastSeedUpload_myIP = "";

    public  PeerActions peerActions;
    public  NewsPool newsPool;

    private int netRedundancy;
    public  PartitionScheme scheme;

    private Seed mySeed; // my own seed
    private final Set<String> myBotIDs; // list of id's that this bot accepts as robots.txt identification
    private final Hashtable<String, String> nameLookupCache; // a name-to-hash relation
    private final Hashtable<InetAddress, SoftReference<Seed>> ipLookupCache;

    public SeedDB(
            final File networkRoot,
            final String seedActiveDBFileName,
            final String seedPassiveDBFileName,
            final String seedPotentialDBFileName,
            final File myOwnSeedFile,
            final int redundancy,
            final int partitionExponent,
            final boolean useTailCache,
            final boolean exceed134217727) {
        this.seedActiveDBFile = new File(networkRoot, seedActiveDBFileName);
        this.seedPassiveDBFile = new File(networkRoot, seedPassiveDBFileName);
        this.seedPotentialDBFile = new File(networkRoot, seedPotentialDBFileName);
        this.mySeed = null; // my own seed
        this.myOwnSeedFile = myOwnSeedFile;
        this.myBotIDs = new HashSet<String>();
        this.myBotIDs.add("yacy");
        this.myBotIDs.add("yacybot");
        this.myBotIDs.add("yacyproxy");
        this.netRedundancy = redundancy;
        this.scheme = new VerticalWordPartitionScheme(partitionExponent);

        // set up seed database
        this.seedActiveDB = openSeedTable(this.seedActiveDBFile);
        this.seedPassiveDB = openSeedTable(this.seedPassiveDBFile);
        this.seedPotentialDB = openSeedTable(this.seedPotentialDBFile);

        // start our virtual DNS service for yacy peers with empty cache
        this.nameLookupCache = new Hashtable<String, String>();

        // cache for reverse name lookup
        this.ipLookupCache = new Hashtable<InetAddress, SoftReference<Seed>>();

        // check if we are in the seedCaches: this can happen if someone else published our seed
        removeMySeed();

        this.lastSeedUpload_seedDBSize = sizeConnected();

        // tell the httpdProxy how to find this table as address resolver
        HTTPDemon.setAlternativeResolver(this);

        // create or init news database
        this.newsPool = new NewsPool(networkRoot, useTailCache, exceed134217727);

        // deploy peer actions
        this.peerActions = new PeerActions(this, this.newsPool);
    }

    public void relocate(
            final File newNetworkRoot,
            final int redundancy,
            final int partitionExponent,
            final boolean useTailCache,
            final boolean exceed134217727) {

        // close old databases
        this.seedActiveDB.close();
        this.seedPassiveDB.close();
        this.seedPotentialDB.close();
        this.newsPool.close();
        this.peerActions.close();

        // open new according to the newNetworkRoot
        this.seedActiveDBFile = new File(newNetworkRoot, this.seedActiveDBFile.getName());
        this.seedPassiveDBFile = new File(newNetworkRoot, this.seedPassiveDBFile.getName());
        this.seedPotentialDBFile = new File(newNetworkRoot, this.seedPotentialDBFile.getName());

        // replace my (old) seed with new seed definition from other network
        // but keep the seed name
        final String peername = myName();
        this.mySeed = null; // my own seed
        this.myOwnSeedFile = new File(newNetworkRoot, SeedDB.DBFILE_OWN_SEED);
        initMySeed();
        this.mySeed.setName(peername);

        this.netRedundancy = redundancy;
        this.scheme = new VerticalWordPartitionScheme(partitionExponent);

        // set up seed database
        this.seedActiveDB = openSeedTable(this.seedActiveDBFile);
        this.seedPassiveDB = openSeedTable(this.seedPassiveDBFile);
        this.seedPotentialDB = openSeedTable(this.seedPotentialDBFile);

        // start our virtual DNS service for yacy peers with empty cache
        this.nameLookupCache.clear();

        // cache for reverse name lookup
        this.ipLookupCache.clear();

        // check if we are in the seedCaches: this can happen if someone else published our seed
        removeMySeed();

        this.lastSeedUpload_seedDBSize = sizeConnected();

        // tell the httpdProxy how to find this table as address resolver
        HTTPDemon.setAlternativeResolver(this);

        // create or init news database
        this.newsPool = new NewsPool(newNetworkRoot, useTailCache, exceed134217727);

        // deploy peer actions
        this.peerActions = new PeerActions(this, this.newsPool);
    }

    private synchronized void initMySeed() {
        if (this.mySeed != null) return;

        // create or init own seed
        if (this.myOwnSeedFile.length() > 0) try {
            // load existing identity
            this.mySeed = Seed.load(this.myOwnSeedFile);
            if (this.mySeed == null) throw new IOException("current seed is null");
        } catch (final IOException e) {
            // create new identity
            Log.logSevere("SEEDDB", "could not load stored mySeed.txt from " + this.myOwnSeedFile.toString() + ": " + e.getMessage() + ". creating new seed.", e);
            this.mySeed = Seed.genLocalSeed(this);
            try {
                this.mySeed.save(this.myOwnSeedFile);
            } catch (final IOException ee) {
                Log.logSevere("SEEDDB", "error saving mySeed.txt (1) to " + this.myOwnSeedFile.toString() + ": " + ee.getMessage(), ee);
                Log.logException(ee);
                System.exit(-1);
            }
        } else {
            // create new identity
            Log.logInfo("SEEDDB", "could not find stored mySeed.txt at " + this.myOwnSeedFile.toString() + ": " + ". creating new seed.");
            this.mySeed = Seed.genLocalSeed(this);
            try {
                this.mySeed.save(this.myOwnSeedFile);
            } catch (final IOException ee) {
                Log.logSevere("SEEDDB", "error saving mySeed.txt (2) to " + this.myOwnSeedFile.toString() + ": " + ee.getMessage(), ee);
                Log.logException(ee);
                System.exit(-1);
            }
        }
        this.myBotIDs.add(this.mySeed.getName() + ".yacy");
        this.myBotIDs.add(this.mySeed.hash + ".yacyh");
        this.mySeed.setIP("");       // we delete the old information to see what we have now
        this.mySeed.put(Seed.PEERTYPE, Seed.PEERTYPE_VIRGIN); // markup startup condition
    }

    public Set<String> myBotIDs() {
        return this.myBotIDs;
    }

    public int redundancy() {
        if (this.mySeed.isJunior()) return 1;
        return this.netRedundancy;
    }

    public boolean mySeedIsDefined() {
        return this.mySeed != null;
    }

    public Seed mySeed() {
        if (this.mySeed == null) {
            if (sizeConnected() == 0) try {Thread.sleep(5000);} catch (final InterruptedException e) {} // wait for init
            initMySeed();
            // check if my seed has an IP assigned
            if (myIP() == null || myIP().length() == 0) {
                this.mySeed.setIP(Domains.myPublicLocalIP().getHostAddress());
            }
        }
        return this.mySeed;
    }

    public void setMyName(final String name) {
        this.myBotIDs.remove(this.mySeed.getName() + ".yacy");
        this.mySeed.setName(name);
        this.myBotIDs.add(name + ".yacy");
    }

    public String myAlternativeAddress() {
        return mySeed().getName() + ".yacy";
    }

    public String myIP() {
        return mySeed().getIP();
    }

    public int myPort() {
        return mySeed().getPort();
    }

    public String myName() {
        return this.mySeed.getName();
    }

    public String myID() {
        return this.mySeed.hash;
    }

    public synchronized void removeMySeed() {
        if (this.seedActiveDB.isEmpty() && this.seedPassiveDB.isEmpty() && this.seedPotentialDB.isEmpty()) return; // avoid that the own seed is initialized too early
        if (this.mySeed == null) initMySeed();
        try {
            final byte[] mySeedHash = ASCII.getBytes(this.mySeed.hash);
            this.seedActiveDB.delete(mySeedHash);
            this.seedPassiveDB.delete(mySeedHash);
            this.seedPotentialDB.delete(mySeedHash);
        } catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }

    public void saveMySeed() {
        try {
          mySeed().save(this.myOwnSeedFile);
        } catch (final IOException e) { Log.logWarning("yacySeedDB", "could not save mySeed '"+ this.myOwnSeedFile +"': "+ e.getMessage()); }
    }

    public boolean noDHTActivity() {
        // for small networks, we don't perform DHT transmissions, because it is possible to search over all peers
        return sizeConnected() <= dhtActivityMagic;
    }

    private synchronized MapDataMining openSeedTable(final File seedDBFile) {
        final File parentDir = new File(seedDBFile.getParent());
        if (!parentDir.exists()) {
			if(!parentDir.mkdirs())
				Log.logWarning("yacySeedDB", "could not create directories for "+ seedDBFile.getParent());
		}
        try {
            return new MapDataMining(seedDBFile, Word.commonHashLength, Base64Order.enhancedCoder, 1024 * 512, 500, sortFields, longaccFields, doubleaccFields, this);
        } catch (final Exception e) {
            // try again
            FileUtils.deletedelete(seedDBFile);
            try {
                return new MapDataMining(seedDBFile, Word.commonHashLength, Base64Order.enhancedCoder, 1024 * 512, 500, sortFields, longaccFields, doubleaccFields, this);
            } catch (final IOException e1) {
                Log.logException(e1);
                System.exit(-1);
                return null;
            }
        }
    }

    private synchronized MapDataMining resetSeedTable(MapDataMining seedDB, final File seedDBFile) {
        // this is an emergency function that should only be used if any problem with the
        // seed.db is detected
        Network.log.logWarning("seed-db " + seedDBFile.toString() + " reset (on-the-fly)");
        seedDB.close();
        FileUtils.deletedelete(seedDBFile);
        if (seedDBFile.exists())
        	Log.logWarning("yacySeedDB", "could not delete file "+ seedDBFile);
        // create new seed database
        seedDB = openSeedTable(seedDBFile);
        return seedDB;
    }

    public synchronized void resetActiveTable() { this.seedActiveDB = resetSeedTable(this.seedActiveDB, this.seedActiveDBFile); }
    private synchronized void resetPassiveTable() { this.seedPassiveDB = resetSeedTable(this.seedPassiveDB, this.seedPassiveDBFile); }
    private synchronized void resetPotentialTable() { this.seedPotentialDB = resetSeedTable(this.seedPotentialDB, this.seedPotentialDBFile); }

    public void close() {
        if (this.seedActiveDB != null) this.seedActiveDB.close();
        if (this.seedPassiveDB != null) this.seedPassiveDB.close();
        if (this.seedPotentialDB != null) this.seedPotentialDB.close();
        this.newsPool.close();
        this.peerActions.close();
    }

    public Iterator<Seed> seedsSortedConnected(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, this.seedActiveDB);
    }

    public Iterator<Seed> seedsSortedDisconnected(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, this.seedPassiveDB);
    }

    public Iterator<Seed> seedsSortedPotential(final boolean up, final String field) {
        // enumerates seed-type objects: all seeds sequentially ordered by field
        return new seedEnum(up, field, this.seedPotentialDB);
    }

    public TreeMap<byte[], String> /* peer-b64-hashes/ipport */ clusterHashes(final String clusterdefinition) {
    	// collects seeds according to cluster definition string, which consists of
    	// comma-separated .yacy or .yacyh-domains
    	// the domain may be extended by an alternative address specification of the form
    	// <ip> or <ip>:<port>. The port must be identical to the port specified in the peer seed,
    	// therefore it is optional. The address specification is separated by a '='; the complete
    	// address has therefore the form
    	// address    ::= (<peername>'.yacy'|<peerhexhash>'.yacyh'){'='<ip>{':'<port}}
    	// clusterdef ::= {address}{','address}*
    	final String[] addresses = (clusterdefinition.length() == 0) ? new String[0] : clusterdefinition.split(",");
    	final TreeMap<byte[], String> clustermap = new TreeMap<byte[], String>(Base64Order.enhancedCoder);
    	Seed seed;
    	String hash, yacydom, ipport;
    	int p;
    	for (final String addresse : addresses) {
    		p = addresse.indexOf('=');
    		if (p >= 0) {
    			yacydom = addresse.substring(0, p);
    			ipport  = addresse.substring(p + 1);
    		} else {
    			yacydom = addresse;
    			ipport  = null;
    		}
    		if (yacydom.endsWith(".yacyh")) {
    			// find a peer with its hexhash
    			hash = Seed.hexHash2b64Hash(yacydom.substring(0, yacydom.length() - 6));
    			seed = get(hash);
    			if (seed == null) {
    				Network.log.logWarning("cluster peer '" + yacydom + "' was not found.");
    			} else {
    				clustermap.put(ASCII.getBytes(hash), ipport);
    			}
    		} else if (yacydom.endsWith(".yacy")) {
    			// find a peer with its name
    			seed = lookupByName(yacydom.substring(0, yacydom.length() - 5));
    			if (seed == null) {
    				Network.log.logWarning("cluster peer '" + yacydom + "' was not found.");
    			} else {
    				clustermap.put(ASCII.getBytes(seed.hash), ipport);
    			}
    		} else {
    			Network.log.logWarning("cluster peer '" + addresse + "' has wrong syntax. the name must end with .yacy or .yacyh");
    		}
    	}
    	return clustermap;
    }

    public Iterator<Seed> seedsConnected(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, this.seedActiveDB, minVersion);
    }

    private Iterator<Seed> seedsDisconnected(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, this.seedPassiveDB, minVersion);
    }

    private Iterator<Seed> seedsPotential(final boolean up, final boolean rot, final byte[] firstHash, final float minVersion) {
        // enumerates seed-type objects: all seeds sequentially without order
        return new seedEnum(up, rot, (firstHash == null) ? null : firstHash, null, this.seedPotentialDB, minVersion);
    }

    public Seed anySeedVersion(final float minVersion) {
        // return just any seed that has a specific minimum version number
        final Iterator<Seed> e = seedsConnected(true, true, Seed.randomHash(), minVersion);
        return e.next();
    }

    /**
     * count the number of peers that had been seed within the time limit
     * @param limit the time limit in minutes. 1440 minutes is a day
     * @return the number of peers seen in the given time
     */
    public int sizeActiveSince(final long limit) {
        int c = this.seedActiveDB.size();
        Seed seed;
        Iterator<Seed> i = seedsSortedDisconnected(false, Seed.LASTSEEN);
        while (i.hasNext()) {
            seed = i.next();
            if (seed != null) {
                if (Math.abs((System.currentTimeMillis() - seed.getLastSeenUTC()) / 1000 / 60) > limit) break;
                c++;
            }
        }
        i = seedsSortedPotential(false, Seed.LASTSEEN);
        while (i.hasNext()) {
            seed = i.next();
            if (seed != null) {
                if (Math.abs((System.currentTimeMillis() - seed.getLastSeenUTC()) / 1000 / 60) > limit) break;
                c++;
            }
        }
        return c;
    }

     public int sizeConnected() {
        return this.seedActiveDB.size();
    }

    public int sizeDisconnected() {
        return this.seedPassiveDB.size();
    }

    public int sizePotential() {
        return this.seedPotentialDB.size();
    }

    public long countActiveURL() { return this.seedActiveDB.getLongAcc(Seed.LCOUNT); }
    public long countActiveRWI() { return this.seedActiveDB.getLongAcc(Seed.ICOUNT); }
    public long countActivePPM() { return this.seedActiveDB.getLongAcc(Seed.ISPEED); }
    public float countActiveQPM() { return this.seedActiveDB.getFloatAcc(Seed.RSPEED); }
    public long countPassiveURL() { return this.seedPassiveDB.getLongAcc(Seed.LCOUNT); }
    public long countPassiveRWI() { return this.seedPassiveDB.getLongAcc(Seed.ICOUNT); }
    public long countPotentialURL() { return this.seedPotentialDB.getLongAcc(Seed.LCOUNT); }
    public long countPotentialRWI() { return this.seedPotentialDB.getLongAcc(Seed.ICOUNT); }

    public void addConnected(final Seed seed) {
        if (seed.isProper(false) != null) return;
        //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
        synchronized (this) {
            try {
                this.nameLookupCache.put(seed.getName(), seed.hash);
                final ConcurrentMap<String, String> seedPropMap = seed.getMap();
                this.seedActiveDB.insert(ASCII.getBytes(seed.hash), seedPropMap);
                this.seedPassiveDB.delete(ASCII.getBytes(seed.hash));
                this.seedPotentialDB.delete(ASCII.getBytes(seed.hash));
            } catch (final Exception e) {
                Network.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                resetActiveTable();
            }
        }
    }

    protected void addDisconnected(final Seed seed) {
        if (seed.isProper(false) != null) return;
        synchronized (this) {
            try {
                this.nameLookupCache.remove(seed.getName());
                this.seedActiveDB.delete(ASCII.getBytes(seed.hash));
                this.seedPotentialDB.delete(ASCII.getBytes(seed.hash));
            } catch (final Exception e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
            //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
            try {
                final ConcurrentMap<String, String> seedPropMap = seed.getMap();
                this.seedPassiveDB.insert(ASCII.getBytes(seed.hash), seedPropMap);
            } catch (final Exception e) {
                Network.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                resetPassiveTable();
            }
        }
    }

    protected void addPotential(final Seed seed) {
        if (seed.isProper(false) != null) return;
        synchronized (this) {
            try {
                this.nameLookupCache.remove(seed.getName());
                this.seedActiveDB.delete(ASCII.getBytes(seed.hash));
                this.seedPassiveDB.delete(ASCII.getBytes(seed.hash));
            } catch (final Exception e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
            //seed.put(yacySeed.LASTSEEN, yacyCore.shortFormatter.format(new Date(yacyCore.universalTime())));
            try {
                final ConcurrentMap<String, String> seedPropMap = seed.getMap();
                this.seedPotentialDB.insert(ASCII.getBytes(seed.hash), seedPropMap);
            } catch (final Exception e) {
                Network.log.logSevere("ERROR add: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                resetPotentialTable();
            }
        }
    }

    public synchronized void removeDisconnected(final String peerHash) {
    	if (peerHash == null) return;
    	try {
			this.seedPassiveDB.delete(ASCII.getBytes(peerHash));
		} catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }

    public synchronized void removePotential(final String peerHash) {
    	if (peerHash == null) return;
    	try {
			this.seedPotentialDB.delete(ASCII.getBytes(peerHash));
		} catch (final IOException e) { Log.logWarning("yacySeedDB", "could not remove hash ("+ e.getClass() +"): "+ e.getMessage()); }
    }

    public boolean hasConnected(final byte[] hash) {
        return this.seedActiveDB.containsKey(hash);
    }

    public boolean hasDisconnected(final byte[] hash) {
        return this.seedPassiveDB.containsKey(hash);
    }

    public boolean hasPotential(final byte[] hash) {
        return this.seedPotentialDB.containsKey(hash);
    }

    private Seed get(final String hash, final MapDataMining database) {
        if (hash == null || hash.length() == 0) return null;
        if ((this.mySeed != null) && (hash.equals(this.mySeed.hash))) return this.mySeed;
        final ConcurrentHashMap<String, String> entry = new ConcurrentHashMap<String, String>();
        try {
            final Map<String, String> map = database.get(ASCII.getBytes(hash));
            if (map == null) return null;
            entry.putAll(map);
        } catch (final IOException e) {
            Log.logException(e);
            return null;
        } catch (final RowSpaceExceededException e) {
            Log.logException(e);
            return null;
        }
        return new Seed(hash, entry);
    }

    public Seed getConnected(final String hash) {
        return get(hash, this.seedActiveDB);
    }

    public Seed getDisconnected(final String hash) {
        return get(hash, this.seedPassiveDB);
    }

    public Seed getPotential(final String hash) {
        return get(hash, this.seedPotentialDB);
    }

    public Seed get(final String hash) {
        Seed seed = getConnected(hash);
        if (seed == null) seed = getDisconnected(hash);
        if (seed == null) seed = getPotential(hash);
        return seed;
    }

    public void update(final String hash, final Seed seed) {
        if (this.mySeed == null) initMySeed();
        if (hash.equals(this.mySeed.hash)) {
            this.mySeed = seed;
            return;
        }
        final byte[] hashb = ASCII.getBytes(hash);
        Seed s = get(hash, this.seedActiveDB);
        if (s != null) try { this.seedActiveDB.insert(hashb, seed.getMap()); return;} catch (final Exception e) {Log.logException(e);}

        s = get(hash, this.seedPassiveDB);
        if (s != null) try { this.seedPassiveDB.insert(hashb, seed.getMap()); return;} catch (final Exception e) {Log.logException(e);}

        s = get(hash, this.seedPotentialDB);
        if (s != null) try { this.seedPotentialDB.insert(hashb, seed.getMap()); return;} catch (final Exception e) {Log.logException(e);}
    }

    public Seed lookupByName(String peerName) {
        // reads a seed by searching by name
        if (peerName.endsWith(".yacy")) peerName = peerName.substring(0, peerName.length() - 5);

        // local peer?
        if (peerName.equals("localpeer")) {
            if (this.mySeed == null) initMySeed();
            return this.mySeed;
        }

        // then try to use the cache
        final String seedhash = this.nameLookupCache.get(peerName);
        Seed seed;
        if (seedhash != null) {
        	seed = this.get(seedhash);
        	if (seed != null) return seed;
        }

        // enumerate the cache and simultanous insert values
        String name;
    	for (int table = 0; table < 2; table++) {
            final Iterator<Seed> e = (table == 0) ? seedsConnected(true, false, null, (float) 0.0) : seedsDisconnected(true, false, null, (float) 0.0);
        	while (e.hasNext()) {
        		seed = e.next();
        		if (seed != null) {
        			name = seed.getName().toLowerCase();
        			if (seed.isProper(false) == null) this.nameLookupCache.put(name, seed.hash);
        			if (name.equals(peerName)) return seed;
        		}
        	}
        }
        // check local seed
        if (this.mySeed == null) initMySeed();
        name = this.mySeed.getName().toLowerCase();
        if (this.mySeed.isProper(false) == null) this.nameLookupCache.put(name, this.mySeed.hash);
        if (name.equals(peerName)) return this.mySeed;
        // nothing found
        return null;
    }

    public Seed lookupByIP(
            final InetAddress peerIP,
            final boolean lookupConnected,
            final boolean lookupDisconnected,
            final boolean lookupPotential
    ) {

        if (peerIP == null) return null;
        Seed seed = null;

        // local peer?
        if (Domains.isThisHostIP(peerIP)) {
            if (this.mySeed == null) initMySeed();
            return this.mySeed;
        }

        // then try to use the cache
        final SoftReference<Seed> ref = this.ipLookupCache.get(peerIP);
        if (ref != null) {
            seed = ref.get();
            if (seed != null) return seed;
        }

        int pos = -1;
        String addressStr = null;
        InetAddress seedIPAddress = null;
        final HandleSet badPeerHashes = new HandleSet(12, Base64Order.enhancedCoder, 0);

        if (lookupConnected) {
            // enumerate the cache and simultanous insert values
            final Iterator<Seed> e = seedsConnected(true, false, null, (float) 0.0);
            while (e.hasNext()) {
                seed = e.next();
                if (seed != null) {
                    addressStr = seed.getPublicAddress();
                    if (addressStr == null) {
                    	Log.logWarning("YACY","lookupByIP/Connected: address of seed " + seed.getName() + "/" + seed.hash + " is null.");
                    	try {
                            badPeerHashes.put(ASCII.getBytes(seed.hash));
                        } catch (final RowSpaceExceededException e1) {
                            Log.logException(e1);
                            break;
                        }
                    	continue;
                    }
                    if ((pos = addressStr.indexOf(':'))!= -1) {
                        addressStr = addressStr.substring(0,pos);
                    }
                    seedIPAddress = Domains.dnsResolve(addressStr);
                    if (seedIPAddress == null) continue;
                    if (seed.isProper(false) == null) this.ipLookupCache.put(seedIPAddress, new SoftReference<Seed>(seed));
                    if (seedIPAddress.equals(peerIP)) return seed;
                }
            }
            // delete bad peers
            final Iterator<byte[]> i = badPeerHashes.iterator();
            while (i.hasNext()) try {this.seedActiveDB.delete(i.next());} catch (final IOException e1) {Log.logException(e1);}
            badPeerHashes.clear();
        }

        if (lookupDisconnected) {
            // enumerate the cache and simultanous insert values
            final Iterator<Seed>e = seedsDisconnected(true, false, null, (float) 0.0);

            while (e.hasNext()) {
                seed = e.next();
                if (seed != null) {
                    addressStr = seed.getPublicAddress();
                    if (addressStr == null) {
                        Log.logWarning("YACY","lookupByIPDisconnected: address of seed " + seed.getName() + "/" + seed.hash + " is null.");
                        try {
                            badPeerHashes.put(UTF8.getBytes(seed.hash));
                        } catch (final RowSpaceExceededException e1) {
                            Log.logException(e1);
                            break;
                        }
                        continue;
                    }
                    if ((pos = addressStr.indexOf(':'))!= -1) {
                        addressStr = addressStr.substring(0,pos);
                    }
                    seedIPAddress = Domains.dnsResolve(addressStr);
                    if (seedIPAddress == null) continue;
                    if (seed.isProper(false) == null) this.ipLookupCache.put(seedIPAddress, new SoftReference<Seed>(seed));
                    if (seedIPAddress.equals(peerIP)) return seed;
                }
            }
            // delete bad peers
            final Iterator<byte[]> i = badPeerHashes.iterator();
            while (i.hasNext()) try {this.seedActiveDB.delete(i.next());} catch (final IOException e1) {Log.logException(e1);}
            badPeerHashes.clear();
        }

        if (lookupPotential) {
            // enumerate the cache and simultanous insert values
            final Iterator<Seed> e = seedsPotential(true, false, null, (float) 0.0);

            while (e.hasNext()) {
                seed = e.next();
                if ((seed != null) && ((addressStr = seed.getPublicAddress()) != null)) {
                    if ((pos = addressStr.indexOf(':'))!= -1) {
                        addressStr = addressStr.substring(0,pos);
                    }
                    seedIPAddress = Domains.dnsResolve(addressStr);
                    if (seedIPAddress == null) continue;
                    if (seed.isProper(false) == null) this.ipLookupCache.put(seedIPAddress, new SoftReference<Seed>(seed));
                    if (seedIPAddress.equals(peerIP)) return seed;
                }
            }
        }

        // check local seed
        if (this.mySeed == null) return null;
        addressStr = this.mySeed.getPublicAddress();
        if (addressStr == null) return null;
        if ((pos = addressStr.indexOf(':'))!= -1) {
            addressStr = addressStr.substring(0,pos);
        }
        seedIPAddress = Domains.dnsResolve(addressStr);
        if (seedIPAddress == null) return null;
        if (this.mySeed.isProper(false) == null) this.ipLookupCache.put(seedIPAddress,  new SoftReference<Seed>(this.mySeed));
        if (seedIPAddress.equals(peerIP)) return this.mySeed;
        // nothing found
        return null;
    }

    private ArrayList<String> storeSeedList(final File seedFile, final boolean addMySeed) throws IOException {
        PrintWriter pw = null;
        final ArrayList<String> v = new ArrayList<String>(this.seedActiveDB.size() + 1);
        try {

            pw = new PrintWriter(new BufferedWriter(new FileWriter(seedFile)));

            // store own peer seed
            String line;
            if (this.mySeed == null) initMySeed();
            if (addMySeed) {
                line = this.mySeed.genSeedStr(null);
                v.add(line);
                pw.print(line + serverCore.CRLF_STRING);
            }

            // store active peer seeds
            Seed ys;
            Iterator<Seed> se = seedsConnected(true, false, null, (float) 0.0);
            while (se.hasNext()) {
                ys = se.next();
                if (ys != null) {
                    line = ys.genSeedStr(null);
                    v.add(line);
                    pw.print(line + serverCore.CRLF_STRING);
                }
            }

            // store some of the not-so-old passive peer seeds (limit: 1 day)
            se = seedsDisconnected(true, false, null, (float) 0.0);
            final long timeout = System.currentTimeMillis() - (1000L * 60L * 60L * 24L);
            while (se.hasNext()) {
                ys = se.next();
                if (ys != null) {
                    if (ys.getLastSeenUTC() < timeout) continue;
                    line = ys.genSeedStr(null);
                    v.add(line);
                    pw.print(line + serverCore.CRLF_STRING);
                }
            }
            pw.flush();
        } finally {
            if (pw != null) try { pw.close(); } catch (final Exception e) {}
        }
        return v;
    }

    protected String uploadSeedList(final yacySeedUploader uploader,
            final serverSwitch sb,
            final SeedDB seedDB,
            final DigestURI seedURL) throws Exception {

        // upload a seed file, if possible
        if (seedURL == null) throw new NullPointerException("UPLOAD - Error: URL not given");

        String log = null;
        File seedFile = null;
        try {
            // create a seed file which for uploading ...
            seedFile = File.createTempFile("seedFile",".txt", seedDB.myOwnSeedFile.getParentFile());
            seedFile.deleteOnExit();
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Storing seedlist into tempfile " + seedFile.toString());
            final ArrayList<String> uv = storeSeedList(seedFile, true);

            // uploading the seed file
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Trying to upload seed-file, " + seedFile.length() + " bytes, " + uv.size() + " entries.");
            log = uploader.uploadSeedFile(sb, seedFile);

            // test download
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Trying to download seed-file '" + seedURL + "'.");
            final Iterator<String> check = downloadSeedFile(seedURL);

            // Comparing if local copy and uploaded copy are equal
            final String errorMsg = checkCache(uv, check);
            if (errorMsg == null)
                log = log + "UPLOAD CHECK - Success: the result vectors are equal" + serverCore.CRLF_STRING;
            else {
                throw new Exception("UPLOAD CHECK - Error: the result vector is different. " + errorMsg + serverCore.CRLF_STRING);
            }
        } finally {
            if (seedFile != null)
				try {
				    FileUtils.deletedelete(seedFile);
				} catch (final Exception e) {
					/* ignore this */
				}
        }

        return log;
    }

    private Iterator<String> downloadSeedFile(final DigestURI seedURL) throws IOException {
        // Configure http headers
        final RequestHeader reqHeader = new RequestHeader();
        reqHeader.put(HeaderFramework.PRAGMA, "no-cache");
        reqHeader.put(HeaderFramework.CACHE_CONTROL, "no-cache"); // httpc uses HTTP/1.0 is this necessary?
        reqHeader.put(HeaderFramework.USER_AGENT, ClientIdentification.getUserAgent());

        final HTTPClient client = new HTTPClient();
        client.setHeader(reqHeader.entrySet());
        byte[] content = null;
        try {
            // send request
        	content = client.GETbytes(seedURL);
        } catch (final Exception e) {
        	throw new IOException("Unable to download seed file '" + seedURL + "'. " + e.getMessage());
        }

        // check response code
        if (client.getHttpResponse().getStatusLine().getStatusCode() != 200) {
        	throw new IOException("Server returned status: " + client.getHttpResponse().getStatusLine());
        }

        try {
            // uncompress it if it is gzipped
            content = FileUtils.uncompressGZipArray(content);

            // convert it into an array
            return FileUtils.strings(content);
        } catch (final Exception e) {
        	throw new IOException("Unable to uncompress seed file '" + seedURL + "'. " + e.getMessage());
        }
    }

    private String checkCache(final ArrayList<String> uv, final Iterator<String> check) {
        if ((check == null) || (uv == null)) {
            if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Local and uploades seed-list are different");
            return "Entry count is different: uv.size() = " + ((uv == null) ? "null" : Integer.toString(uv.size()));
        }

        if (Log.isFine("YACY")) Log.logFine("YACY", "SaveSeedList: Comparing local and uploades seed-list entries ...");
        int i = 0;
        while (check.hasNext() && i < uv.size()) {
        	if (!((uv.get(i)).equals(check.next()))) return "Element at position " + i + " is different.";
        	i++;
        }

        // no difference found
        return null;
    }

    /**
     * resolve a yacy address
     */
    public String resolve(String host) {
        Seed seed;
        int p;
        String subdom = null;
        if (host.endsWith(".yacyh")) {
            // this is not functional at the moment
            // caused by lowecasing of hashes at the browser client
            p = host.indexOf('.');
            if ((p > 0) && (p != (host.length() - 6))) {
                subdom = host.substring(0, p);
                host = host.substring(p + 1);
            }
            // check if we have a b64-hash or a hex-hash
            String hash = host.substring(0, host.length() - 6);
            if (hash.length() > Word.commonHashLength) {
                // this is probably a hex-hash
                hash = Seed.hexHash2b64Hash(hash);
            }
            // check remote seeds
            seed = getConnected(hash); // checks only remote, not local
            // check local seed
            if (seed == null) {
                if (this.mySeed == null) initMySeed();
                if (hash.equals(this.mySeed.hash))
                    seed = this.mySeed;
                else return null;
            }
            return seed.getPublicAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else if (host.endsWith(".yacy")) {
            // identify subdomain
            p = host.indexOf('.');
            if ((p > 0) && (p != (host.length() - 5))) {
                subdom = host.substring(0, p); // no double-dot attack possible, the subdom cannot have ".." in it
                host = host.substring(p + 1); // if ever, the double-dots are here but do not harm
            }
            // identify domain
            final String domain = host.substring(0, host.length() - 5).toLowerCase();
            seed = lookupByName(domain);
            if (seed == null) return null;
            if (this.mySeed == null) initMySeed();
            if ((seed == this.mySeed) && (!(seed.isOnline()))) {
                // take local ip instead of external
                return Switchboard.getSwitchboard().myPublicIP() + ":" + Switchboard.getSwitchboard().getConfig("port", "8090") + ((subdom == null) ? "" : ("/" + subdom));
            }
            return seed.getPublicAddress() + ((subdom == null) ? "" : ("/" + subdom));
        } else {
            return null;
        }
    }

    public String targetAddress(final String targetHash) {
        // find target address
        String address;
        if (targetHash.equals(mySeed().hash)) {
            address = mySeed().getClusterAddress();
        } else {
            final Seed targetSeed = getConnected(targetHash);
            if (targetSeed == null) { return null; }
            address = targetSeed.getClusterAddress();
        }
        if (address == null) address = "localhost:8090";
        return address;
    }

    private class seedEnum implements Iterator<Seed> {

        private MapDataMining.mapIterator it;
        private Seed nextSeed;
        private final MapDataMining database;
        private float minVersion;

        private seedEnum(final boolean up, final boolean rot, final byte[] firstKey, final byte[] secondKey, final MapDataMining database, final float minVersion) {
            this.database = database;
            this.minVersion = minVersion;
            try {
                this.it = (firstKey == null) ? database.maps(up, rot) : database.maps(up, rot, firstKey, secondKey);
                float version;
                while (true) {
                    this.nextSeed = internalNext();
                    if (this.nextSeed == null) break;
                    version = this.nextSeed.getVersion();
                    if (version >= this.minVersion || version == 0.0) break; // include 0.0 to access always developer peers
                }
            } catch (final IOException e) {
                Log.logException(e);
                Network.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == SeedDB.this.seedActiveDB) SeedDB.this.seedActiveDB = resetSeedTable(SeedDB.this.seedActiveDB, SeedDB.this.seedActiveDBFile);
                if (database == SeedDB.this.seedPassiveDB) SeedDB.this.seedPassiveDB = resetSeedTable(SeedDB.this.seedPassiveDB, SeedDB.this.seedPassiveDBFile);
                this.it = null;
            } catch (final kelondroException e) {
                Log.logException(e);
                Network.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == SeedDB.this.seedActiveDB) SeedDB.this.seedActiveDB = resetSeedTable(SeedDB.this.seedActiveDB, SeedDB.this.seedActiveDBFile);
                if (database == SeedDB.this.seedPassiveDB) SeedDB.this.seedPassiveDB = resetSeedTable(SeedDB.this.seedPassiveDB, SeedDB.this.seedPassiveDBFile);
                this.it = null;
            }
        }

        private seedEnum(final boolean up, final String field, final MapDataMining database) {
            this.database = database;
            try {
                this.it = database.maps(up, field);
                this.nextSeed = internalNext();
            } catch (final kelondroException e) {
                Log.logException(e);
                Network.log.logSevere("ERROR seedLinEnum: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (database == SeedDB.this.seedActiveDB) SeedDB.this.seedActiveDB = resetSeedTable(SeedDB.this.seedActiveDB, SeedDB.this.seedActiveDBFile);
                if (database == SeedDB.this.seedPassiveDB) SeedDB.this.seedPassiveDB = resetSeedTable(SeedDB.this.seedPassiveDB, SeedDB.this.seedPassiveDBFile);
                if (database == SeedDB.this.seedPotentialDB) SeedDB.this.seedPotentialDB = resetSeedTable(SeedDB.this.seedPotentialDB, SeedDB.this.seedPotentialDBFile);
                this.it = null;
            }
        }

        public boolean hasNext() {
            return (this.nextSeed != null);
        }

        private Seed internalNext() {
            if (this.it == null || !(this.it.hasNext())) return null;
            try {
                Map<String, String> dna0;
                ConcurrentHashMap<String, String> dna;
                while (this.it.hasNext()) {
                    try {
                        dna0 = this.it.next();
                    } catch (final OutOfMemoryError e) {
                        Log.logException(e);
                        dna0 = null;
                    }
                    assert dna0 != null;
                    if (dna0 == null) continue;
                    if (dna0 instanceof ConcurrentHashMap) {
                        dna = (ConcurrentHashMap<String, String>) dna0;
                    } else {
                        dna = new ConcurrentHashMap<String, String>();
                        dna.putAll(dna0);
                    }
                    final String hash = dna.remove("key");
                    //assert hash != null;
                    if (hash == null) continue; // bad seed
                    return new Seed(hash, dna);
                }
                return null;
            } catch (final Exception e) {
                Log.logException(e);
                Network.log.logSevere("ERROR internalNext: seed.db corrupt (" + e.getMessage() + "); resetting seed.db", e);
                if (this.database == SeedDB.this.seedActiveDB) SeedDB.this.seedActiveDB = resetSeedTable(SeedDB.this.seedActiveDB, SeedDB.this.seedActiveDBFile);
                if (this.database == SeedDB.this.seedPassiveDB) SeedDB.this.seedPassiveDB = resetSeedTable(SeedDB.this.seedPassiveDB, SeedDB.this.seedPassiveDBFile);
                if (this.database == SeedDB.this.seedPotentialDB) SeedDB.this.seedPotentialDB = resetSeedTable(SeedDB.this.seedPotentialDB, SeedDB.this.seedPotentialDBFile);
                return null;
            }
        }

        public Seed next() {
            final Seed seed = this.nextSeed;
            float version;
            try {while (true) {
                this.nextSeed = internalNext();
                if (this.nextSeed == null) break;
                version = this.nextSeed.getVersion();
                if (version >= this.minVersion || version == 0.0) break; // include 0.0 to access always developer peers
            }} catch (final kelondroException e) {
                Log.logException(e);
            	// emergency reset
            	Network.log.logSevere("seed-db emergency reset", e);
            	this.database.clear();
				this.nextSeed = null;
				return null;
            }
            return seed;
        }

        public void remove() {
            throw new UnsupportedOperationException();
        }

    }

}
