package com.trading.account.domain.transaction.dto;

import java.util.List;

public record TransactionHistoryCursorResDto(List<TransactionHistoryResDto> items, Long nextCursor, boolean hasNext) {}
