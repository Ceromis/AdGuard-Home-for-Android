package com.adguardhome.android;

import android.os.IBinder;
import android.os.Parcel;

import java.util.Arrays;
import java.lang.reflect.Method;

public final class DnsSetter {
    private DnsSetter() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("dnssetter: start");
        if (args.length < 1) throw new IllegalArgumentException("netId is required");
        int netId = Integer.parseInt(args[0]);
        System.out.println("dnssetter: netId=" + netId);
        String[] servers = args.length > 1
                ? Arrays.copyOfRange(args, 1, args.length)
                : new String[]{"127.0.0.1"};
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        Method getService = serviceManager.getDeclaredMethod("getService", String.class);
        getService.setAccessible(true);
        IBinder binder = (IBinder) getService.invoke(null, "dnsresolver");
        if (binder == null) throw new IllegalStateException("dnsresolver service is unavailable");
        System.out.println("dnssetter: binder acquired");

        Parcel request = Parcel.obtain();
        Parcel response = Parcel.obtain();
        request.writeInterfaceToken("android.net.IDnsResolver");
        request.writeInt(1);
        int parcelStart = request.dataPosition();
        request.writeInt(0);
        request.writeInt(netId);
        request.writeInt(1800);
        request.writeInt(25);
        request.writeInt(8);
        request.writeInt(64);
        request.writeInt(1000);
        request.writeInt(2);
        request.writeStringArray(servers);
        request.writeStringArray(new String[0]);
        request.writeString("");
        request.writeStringArray(new String[0]);
        request.writeStringArray(new String[0]);
        request.writeString("");
        request.writeInt(0);
        request.writeInt(0);
        request.writeIntArray(new int[]{1});
        int parcelEnd = request.dataPosition();
        request.setDataPosition(parcelStart);
        request.writeInt(parcelEnd - parcelStart);
        request.setDataPosition(parcelEnd);
        System.out.println("dnssetter: applying configuration");
        if (!binder.transact(3, request, response, 0)) throw new IllegalStateException("dnsresolver transaction rejected");
        response.readException();
        request.recycle();
        response.recycle();
        System.out.println("dnsresolver netId " + netId + " -> " + Arrays.toString(servers));
    }
}
