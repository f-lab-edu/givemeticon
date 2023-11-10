package com.jinddung2.givemeticon.domain.trade.facade;

import com.jinddung2.givemeticon.domain.item.domain.Item;
import com.jinddung2.givemeticon.domain.item.service.ItemService;
import com.jinddung2.givemeticon.domain.sale.domain.Sale;
import com.jinddung2.givemeticon.domain.sale.service.SaleService;
import com.jinddung2.givemeticon.domain.trade.domain.Trade;
import com.jinddung2.givemeticon.domain.trade.exception.AlreadyBoughtSaleException;
import com.jinddung2.givemeticon.domain.trade.service.TradeService;
import com.jinddung2.givemeticon.domain.user.exception.NotFoundUserException;
import com.jinddung2.givemeticon.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class TradeCreationFacade {

    private final UserService userService;
    private final SaleService saleService;
    private final ItemService itemService;
    private final TradeService tradeService;

    @Transactional
    public int transact(int saleId, int buyerId) {
        if (!userService.isExists(buyerId)) {
            throw new NotFoundUserException();
        }

        Sale sale = saleService.getSale(saleId);

        if (sale.isBought()) {
            throw new AlreadyBoughtSaleException();
        }

        Item item = itemService.getItem(sale.getItemId());

        long daysUntilExpiration = ChronoUnit.DAYS.between(LocalDate.now(), sale.getExpirationDate());

        Trade trade = Trade.builder()
                .buyerId(buyerId)
                .saleId(saleId)
                .salePrice(BigDecimal.valueOf(item.getPrice()))
                .isUsed(false)
                .build();

        sale.updateBoughtState();
        saleService.update(sale);
        return tradeService.save(trade, daysUntilExpiration);
    }
}
