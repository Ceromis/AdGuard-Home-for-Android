package com.adguardhome.android;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Reads the source URL associated with an imported Clash Android profile. */
public final class ProxyProfileReader {
    private ProxyProfileReader() {
    }

    public static void main(String[] args) {
        if (args.length != 2) throw new IllegalArgumentException("usage: ProxyProfileReader DATABASE UUID");
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(args[0], null, SQLiteDatabase.OPEN_READONLY);
             Cursor cursor = database.rawQuery("SELECT source FROM imported WHERE uuid = ? LIMIT 1", new String[]{args[1]})) {
            if (cursor.moveToFirst()) {
                String source = cursor.getString(0);
                if (source != null && (source.startsWith("https://") || source.startsWith("http://"))) {
                    System.out.println(source);
                }
            }
        }
    }
}
