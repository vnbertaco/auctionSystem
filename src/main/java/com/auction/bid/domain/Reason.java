package com.auction.bid.domain;

public enum Reason {
    BELOW_CURRENT_BID,
    BELOW_STARTING_PRICE,
    SELLER_CANNOT_BID,
    ALREADY_LEADING,
    AUCTION_CLOSED,
    AUCTION_NOT_FOUND
}
