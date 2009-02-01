// kelondroFlexWidthArray.java
// (C) 2006 by Michael Peter Christen; mc@yacy.net, Frankfurt a. M., Germany
// first published 01.06.2006 on http://www.anomic.de
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

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import de.anomic.kelondro.index.Array;
import de.anomic.kelondro.index.Column;
import de.anomic.kelondro.index.Row;
import de.anomic.kelondro.order.NaturalOrder;
import de.anomic.kelondro.util.kelondroException;
import de.anomic.kelondro.util.Log;

public class FlexWidthArray implements Array {

    protected FixedWidthArray[] col;
    protected Row rowdef;
    protected File path;
    protected String tablename;
    protected String filename;
    
    public FlexWidthArray(final File path, final String tablename, final Row rowdef, final boolean resetOnFail) {
    	this.path = path;
    	this.rowdef = rowdef;
        this.tablename = tablename;
        try {
			init();
		} catch (final IOException e) {
			if (resetOnFail) {
				Log.logSevere("kelondroFlexWidthArray", "IOException during initialization of " + new File(path, tablename).toString() + ": reset");
				delete(path, tablename);
				try {
					init();
				} catch (final IOException e1) {
					e1.printStackTrace();
					throw new kelondroException("IOException during initialization of " + new File(path, tablename).toString() + ": cannot reset: " + e1.getMessage());
				}
			} else {
				throw new kelondroException("IOException during initialization of " + new File(path, tablename).toString() + ": not allowed to reset: " + e.getMessage());
			}
		} catch (final kelondroException e) {
			if (resetOnFail) {
				Log.logSevere("kelondroFlexWidthArray", "kelondroException during initialization of " + new File(path, tablename).toString() + ": reset");
				delete(path, tablename);
				try {
					init();
				} catch (final IOException e1) {
					e1.printStackTrace();
					throw new kelondroException("kelondroException during initialization of " + new File(path, tablename).toString() + ": cannot reset: " + e1.getMessage());
				}
			} else {
				throw new kelondroException("kelondroException during initialization of " + new File(path, tablename).toString() + ": not allowed to reset: " + e.getMessage());
			}
		}
   }
    
    public void init() throws IOException {
        
        // initialize columns
        col = new FixedWidthArray[rowdef.columns()];
        String check = "";
        for (int i = 0; i < rowdef.columns(); i++) {
            col[i] = null;
            check += '_';
        }
        
        // check if table directory exists
        final File tabledir = new File(path, tablename);
        if (tabledir.exists()) {
            if (!(tabledir.isDirectory())) throw new IOException("path " + tabledir.toString() + " must be a directory");
        } else {
            tabledir.mkdirs();
            tabledir.mkdir();
        }
        this.filename = tabledir.getCanonicalPath();

        // save/check property file for this array
        /*
        final File propfile = new File(tabledir, "properties");
        Map<String, String> props = new HashMap<String, String>();
        if (propfile.exists()) {
            props = serverFileUtils.loadMap(propfile);
            final String stored_rowdef = props.get("rowdef");
            if ((stored_rowdef != null) && (!(rowdef.subsumes(new Row(stored_rowdef, rowdef.objectOrder, 0))))) {
                System.out.println("FATAL ERROR: stored rowdef '" + stored_rowdef + "' does not match with new rowdef '" + 
                        rowdef + "' for flex table '" + path + "', table " + tablename);
                System.exit(-1);
            }
        }
        props.put("rowdef", rowdef.toString());
        serverFileUtils.saveMap(propfile, props, "FlexWidthArray properties");
        */
        
        // open existing files
        final String[] files = tabledir.list();
        for (int i = 0; i < files.length; i++) {
            if ((files[i].startsWith("col.") && (files[i].endsWith(".list")))) {
                final int colstart = Integer.parseInt(files[i].substring(4, 7));
                final int colend   = (files[i].charAt(7) == '-') ? Integer.parseInt(files[i].substring(8, 11)) : colstart;
                
                final Column columns[] = new Column[colend - colstart + 1];
                for (int j = colstart; j <= colend; j++) columns[j-colstart] = rowdef.column(j);
                col[colstart] = new FixedWidthArray(new File(tabledir, files[i]), new Row(columns, (colstart == 0) ? rowdef.objectOrder : NaturalOrder.naturalOrder, 0), 16);
                for (int j = colstart; j <= colend; j++) check = check.substring(0, j) + "X" + check.substring(j + 1);
            }
        }
        
        // check if all columns are there
        int p, q;
        while ((p = check.indexOf('_')) >= 0) {
            q = p;
            if (p != 0) {
                while ((q <= check.length() - 1) && (check.charAt(q) == '_')) q++;
                q--;
            }
            // create new array file
            final Column[] columns = new Column[q - p + 1];
            for (int j = p; j <= q; j++) {
                columns[j - p] = rowdef.column(j);
                check = check.substring(0, j) + "X" + check.substring(j + 1);
            }
            col[p] = new FixedWidthArray(new File(tabledir, colfilename(p, q)), new Row(columns, (p == 0) ? rowdef.objectOrder : NaturalOrder.naturalOrder, 0), 16);
        }
    }
    
    public final String filename() {
        return this.filename;
    }
    
    public static int staticsize(final File path, final String tablename) {
        
        // check if table directory exists
        final File tabledir = new File(path, tablename);
        if (tabledir.exists()) {
            if (!(tabledir.isDirectory())) return 0;
        } else {
            return 0;
        }

        // open existing files
        final File file = new File(tabledir, "col.000.list");
        return AbstractRecords.staticsize(file);
    }
    
    public static void delete(final File path, final String tablename) {
        final File tabledir = new File(path, tablename);
        if (!(tabledir.exists())) return;
        if ((!(tabledir.isDirectory()))) {
            tabledir.delete();
            return;
        }

        final String[] files = tabledir.list();
        for (int i = 0; i < files.length; i++) {
            new File(tabledir, files[i]).delete();
        }
        
        tabledir.delete();
    }
    
    public void reset() throws IOException {
    	this.close();
    	delete(path, tablename);
    	this.init();
    }
    
    public synchronized void close() {
        if (col != null) {
            for (int i = 0; i < col.length; i++) {
                if (col[i] != null) {
                    // a column can be null, this is normal
                    col[i].close();
                    col[i] = null;
                }
            }
        }
    }
    
    protected static final String colfilename(final int start, final int end) {
        String f = Integer.toString(end);
        while (f.length() < 3) f = "0" + f;
        if (start == end) return "col." + f + ".list";
        f = Integer.toString(start) + "-" + f;
        while (f.length() < 7) f = "0" + f;
        return "col." + f + ".list";
    }
    

    public Row row() {
        return rowdef;
    }
    
    public int size() {
        //assert ((rowdef.columns() == 1) || (col[0].size() == col[1].size())) : "col[0].size() = " + col[0].size() + ", col[1].size() = " + col[1].size() + ", file = " + filename;
        return col[0].size();
    }
    
    public synchronized void setMultiple(final TreeMap<Integer, Row.Entry> entries) throws IOException {
        // a R/W head path-optimized option to write a set of entries
        Iterator<Map.Entry<Integer, Row.Entry>> i;
        Map.Entry<Integer, Row.Entry> entry;
        Row.Entry rowentry, e;
        int c = 0, index;
        // go across each file
        while (c < rowdef.columns()) {
            i = entries.entrySet().iterator();
            while (i.hasNext()) {
                entry = i.next();
                index = entry.getKey().intValue();
                rowentry = entry.getValue();
                assert rowentry.objectsize() == this.rowdef.objectsize;
                        
                e = col[c].row().newEntry(rowentry.bytes(), rowdef.colstart[c], false);
                col[c].set(index, e);             
            }
            c = c + col[c].row().columns();   
        }
    }
    
    public synchronized void set(final int index, final Row.Entry rowentry) throws IOException {
        assert rowentry.objectsize() == this.rowdef.objectsize;
        int c = 0;
        Row.Entry e;
		final byte[] reb = rowentry.bytes();
		while (c < rowdef.columns()) {
			e = col[c].row().newEntry(reb, rowdef.colstart[c], false);
			col[c].set(index, e);
			c = c + col[c].row().columns();
		}
	}
    
    public synchronized int add(final Row.Entry rowentry) throws IOException {
        assert rowentry.objectsize() == this.rowdef.objectsize;
        int index = -1;
		final byte[] reb = rowentry.bytes();
		index = col[0].add(col[0].row().newEntry(reb, 0, false));
		int c = col[0].row().columns();

		while (c < rowdef.columns()) {
			col[c].set(index, col[c].row().newEntry(reb, rowdef.colstart[c], false));
			c = c + col[c].row().columns();
		}
		return index;
    }

	@SuppressWarnings("unchecked")
	protected synchronized TreeMap<Integer, byte[]> addMultiple(final List<Row.Entry> rows) throws IOException {
        // result is a Integer/byte[] relation
        // of newly added rows (index, key)
        final TreeMap<Integer, byte[]> indexref = new TreeMap<Integer, byte[]>();
        Iterator<Row.Entry> i;
        Row.Entry rowentry;
        // prepare storage for other columns
        final TreeMap<Integer, Row.Entry>[] colm = new TreeMap[col.length];
        for (int j = 0; j < col.length; j++) {
            if (col[j] == null) colm[j] = null; else colm[j] = new TreeMap<Integer, Row.Entry>();
        }
        i = rows.iterator();
        while (i.hasNext()) {
            rowentry = i.next();
            assert rowentry.objectsize() == this.rowdef.objectsize;
            
            Row.Entry e;
            int index = -1;
            final byte[] reb = rowentry.bytes();
			e = col[0].row().newEntry(reb, 0, false);
			index = col[0].add(e);
			int c = col[0].row().columns();

			while (c < rowdef.columns()) {
				e = col[c].row().newEntry(reb, rowdef.colstart[c], false);
				// remember write to column, but do not write directly
				colm[c].put(Integer.valueOf(index), e); // col[c].set(index,e);
				c = c + col[c].row().columns();
			}
			indexref.put(Integer.valueOf(index), rowentry.getColBytes(0));
		}
        // write the other columns
        for (int j = 1; j < col.length; j++) {
            if (col[j] != null) col[j].setMultiple(colm[j]);
        }
        // return references to entries with key
        return indexref;
    }
    
    public synchronized Row.Entry get(final int index) throws IOException {
        Row.Entry e = col[0].getIfValid(index);
        //assert e != null;
		if (e == null) return null; // probably a deleted entry
		final Row.Entry p = rowdef.newEntry();
        p.setCol(0, e.getColBytes(0));
		int r = col[0].row().columns();
		while (r < rowdef.columns()) {
			e = col[r].get(index);
			for (int i = 0; i < col[r].row().columns(); i++) {
				p.setCol(r + i, e.getColBytes(i));
			}
			r = r + col[r].row().columns();
		}
		return p;
    }
    
    public synchronized Row.Entry getOmitCol0(final int index, final byte[] col0) throws IOException {
    	assert col[0].row().columns() == 1;
    	final Row.Entry p = rowdef.newEntry();
    	Row.Entry e;
        p.setCol(0, col0);
		int r = 1;
		while (r < rowdef.columns()) {
			e = col[r].get(index);
			for (int i = 0; i < col[r].row().columns(); i++) {
				p.setCol(r + i, e.getColBytes(i));
			}
			r = r + col[r].row().columns();
		}
		return p;
    }

    public synchronized void remove(final int index) throws IOException {
        int r = 0;

		// remove only from the first column
		col[0].remove(index);
		r = r + col[r].row().columns();

		// the other columns will be blanked out only
		while (r < rowdef.columns()) {
			col[r].set(index, null);
			r = r + col[r].row().columns();
		}
    }
    
    public void print() throws IOException {
        System.out.println("PRINTOUT of table, length=" + size());
        Row.Entry row;
        for (int i = 0; i < (col[0].free() + col[0].size()); i++) {
            System.out.print("row " + i + ": ");
            row = get(i);
            System.out.println(row.toString());
            //for (int j = 0; j < row().columns(); j++) System.out.print(((row.empty(j)) ? "NULL" : row.getColString(j, "UTF-8")) + ", ");
            //System.out.println();
        }
        System.out.println("EndOfTable");
    }

    public static void main(final String[] args) {
        //File f = new File("d:\\\\mc\\privat\\fixtest.db");
        final File f = new File("/Users/admin/");
        final Row rowdef = new Row("byte[] a-12, byte[] b-4", NaturalOrder.naturalOrder, 0);
        final String testname = "flextest";
        try {
            System.out.println("erster Test");
            FlexWidthArray.delete(f, testname);
            FlexWidthArray k = new FlexWidthArray(f, "flextest", rowdef, true);
            k.add(k.row().newEntry(new byte[][]{"a".getBytes(), "xxxx".getBytes()}));
            k.add(k.row().newEntry(new byte[][]{"b".getBytes(), "xxxx".getBytes()}));
            k.remove(0);
            
            k.add(k.row().newEntry(new byte[][]{"c".getBytes(), "xxxx".getBytes()}));
            k.add(k.row().newEntry(new byte[][]{"d".getBytes(), "xxxx".getBytes()}));
            k.add(k.row().newEntry(new byte[][]{"e".getBytes(), "xxxx".getBytes()}));
            k.add(k.row().newEntry(new byte[][]{"f".getBytes(), "xxxx".getBytes()}));
            k.remove(0);
            k.remove(1);
            
            k.print();
            k.col[0].print();
            k.col[1].print();
            k.close();
            
            
            System.out.println("zweiter Test");
            FlexWidthArray.delete(f, testname);
            //k = kelondroFlexWidthArray.open(f, "flextest", rowdef);
            for (int i = 1; i <= 20; i = i * 2) {
                System.out.println("LOOP: " + i);
                k = new FlexWidthArray(f, "flextest", rowdef, true);
                for (int j = 0; j < i*2; j++) {
                    k.add(k.row().newEntry(new byte[][]{(Integer.toString(i) + "-" + Integer.toString(j)).getBytes(), "xxxx".getBytes()}));
                }
                k.close();
                k = new FlexWidthArray(f, "flextest", rowdef, true);
                for (int j = 0; j < i; j++) {
                    k.remove(i*2 - j - 1);
                }
                k.close();
            }
            k = new FlexWidthArray(f, "flextest", rowdef, true);
            k.print();
            k.col[0].print();
            k.close();
            
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }
    
    public void deleteOnExit() {
        for (int i = 0; i < this.col.length; i++) this.col[i].deleteOnExit();
    }
}