package com.capri.player;

import android.os.Parcel;
import android.os.Parcelable;

public class Track implements Parcelable {
    public long id;
    public String title;
    public String artist;
    public String album;
    public String data;
    public long duration;
    public long dateAdded;
    public long size;

    public Track() {}

    protected Track(Parcel in) {
        id = in.readLong();
        title = in.readString();
        artist = in.readString();
        album = in.readString();
        data = in.readString();
        duration = in.readLong();
        dateAdded = in.readLong();
        size = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(id);
        dest.writeString(title);
        dest.writeString(artist);
        dest.writeString(album);
        dest.writeString(data);
        dest.writeLong(duration);
        dest.writeLong(dateAdded);
        dest.writeLong(size);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<Track> CREATOR = new Creator<Track>() {
        @Override
        public Track createFromParcel(Parcel in) { return new Track(in); }
        @Override
        public Track[] newArray(int size) { return new Track[size]; }
    };
}
