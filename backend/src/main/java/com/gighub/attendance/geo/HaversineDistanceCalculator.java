package com.gighub.attendance.geo;

/** 두 좌표 사이의 지표면 거리를 계산하는 순수 함수입니다. */
public final class HaversineDistanceCalculator {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private HaversineDistanceCalculator() {
    }

    public static double distanceMeters(
            double latitude1, double longitude1, double latitude2, double longitude2) {
        double lat1 = Math.toRadians(latitude1);
        double lat2 = Math.toRadians(latitude2);
        double deltaLat = Math.toRadians(latitude2 - latitude1);
        double deltaLon = Math.toRadians(longitude2 - longitude1);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2)
                        * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_METERS * c;
    }
}
