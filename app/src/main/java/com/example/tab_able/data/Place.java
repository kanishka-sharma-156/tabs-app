package com.example.tab_able.data;

public class Place {
    private String placeId;
    private String name;
    private double lat;
    private double lng;

    public Place() {}

    public Place(String placeId, String name, double lat, double lng) {
        this.placeId = placeId != null ? placeId : "";
        this.name = name != null ? name : "";
        this.lat = lat;
        this.lng = lng;
    }

    public String getPlaceId() { return placeId != null ? placeId : ""; }
    public void setPlaceId(String placeId) { this.placeId = placeId; }

    public String getName() { return name != null ? name : ""; }
    public void setName(String name) { this.name = name; }

    public double getLat() { return lat; }
    public void setLat(double lat) { this.lat = lat; }

    public double getLng() { return lng; }
    public void setLng(double lng) { this.lng = lng; }
}
