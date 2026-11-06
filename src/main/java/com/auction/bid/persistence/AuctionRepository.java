package com.auction.bid.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuctionRepository extends JpaRepository<AuctionEntity, UUID> {

    /**
     * Lock otimista (Princípio III). Retorna 0 quando a versão esperada não bate (colisão).
     * flush antes e clear depois: a entidade lida antes fica obsoleta após o UPDATE em massa.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AuctionEntity a
               set a.highestBid = :highestBid,
                   a.highestUser = :highestUser,
                   a.version = a.version + 1
             where a.auctionId = :auctionId
               and a.version = :expectedVersion
            """)
    int updateLeader(@Param("auctionId") UUID auctionId,
                     @Param("highestBid") BigDecimal highestBid,
                     @Param("highestUser") String highestUser,
                     @Param("expectedVersion") long expectedVersion);

    @Query("select a.riskTier from AuctionEntity a where a.auctionId = :auctionId")
    Optional<String> findRiskTier(@Param("auctionId") UUID auctionId);
}
