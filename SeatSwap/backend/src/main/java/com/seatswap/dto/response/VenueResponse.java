package com.seatswap.dto.response;

import com.seatswap.domain.Venue;

public record VenueResponse(Long id, String name, String address) {
    public static VenueResponse from(Venue venue) {
        return new VenueResponse(venue.getId(), venue.getName(), venue.getAddress());
    }
}
