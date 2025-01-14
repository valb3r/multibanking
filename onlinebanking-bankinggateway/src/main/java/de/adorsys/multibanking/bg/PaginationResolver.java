package de.adorsys.multibanking.bg;

import com.squareup.okhttp.Call;
import de.adorsys.multibanking.domain.Balance;
import de.adorsys.multibanking.domain.BalancesReport;
import de.adorsys.multibanking.domain.Booking;
import de.adorsys.multibanking.domain.response.TransactionsResponse;
import de.adorsys.multibanking.xs2a_adapter.ApiException;
import de.adorsys.multibanking.xs2a_adapter.ApiResponse;
import de.adorsys.multibanking.xs2a_adapter.api.AccountInformationServiceAisApi;
import de.adorsys.multibanking.xs2a_adapter.model.*;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

import static de.adorsys.multibanking.bg.ApiClientFactory.accountInformationServiceAisApi;

/**
 * Some banks (e.g. Fiducia) don't deliver all transactions in one chunk.
 * Instead they deliver for example the first 150 transactions in the first JSON and provide a "next" link.
 * This link must be used to fetch the next 150 transaction and so on until all transactions are fetched.
 * <p>
 * This class also parses the JSON and map it into TransactionsResponse
 * If a transaction details link is present in the Transaction, the link will be resolved
 * If no balance information is present in the transactions report, a separate call to the balance endpoint will be tried
 */
@Slf4j
public class PaginationResolver {
    private static final String FIDUCIA_PAGINATION_QUERY_PARAMETER = "scrollRef";
    private static final String COMMERZBANK_PAGINATION_QUERY_PARAMETER = "page";
    private static final String BALANCES_LINK_KEY = "balances";
    private static final int MAX_PAGES = 50; // prevent infinite loops

    private final String xs2aAdapterBaseUrl;
    private final BankingGatewayMapper bankingGatewayMapper = new BankingGatewayMapperImpl();

    public PaginationResolver(String xs2aAdapterBaseUrl) {
        this.xs2aAdapterBaseUrl = xs2aAdapterBaseUrl;
    }

    public TransactionsResponse jsonStringToLoadBookingsResponse(String json, PaginationNextCallParameters nextCallParams) throws Exception {
        TransactionsResponse200Json transactionsResponse200JsonTO =
            GsonConfig.getGson().fromJson(json, TransactionsResponse200Json.class);

        List<Booking> bookings = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getBooked)
            .stream()
            .flatMap(Collection::stream)
            .map(bankingGatewayMapper::toBooking)
            .collect(Collectors.toList());

        BalancesReport balancesReport = new BalancesReport();
        Balance openingBookedBalance = new Balance();
        BalanceList balanceList = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getBalances)
            .orElse(null);

        if (balanceList == null) {
            balanceList = resolveBalanceListFromLink(nextCallParams, transactionsResponse200JsonTO);
        }

        Optional.ofNullable(balanceList)
            .stream()
            .flatMap(Collection::stream)
            .filter(balance -> balance.getBalanceType() != null)
            .forEach(balance -> {
                switch (balance.getBalanceType()) {
                    case EXPECTED:
                        balancesReport.setUnreadyBalance(bankingGatewayMapper.toBalance(balance));
                        break;
                    case CLOSINGBOOKED:
                        balancesReport.setReadyBalance(bankingGatewayMapper.toBalance(balance));
                        break;
                    case OPENINGBOOKED:
                        openingBookedBalance.setAmount(bankingGatewayMapper.toBalance(balance).getAmount());
                        break;
                    default:
                        // ignore
                        break;
                }
            });

        // Pagination. If "next" link is present
        Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getLinks)
            .map(linksAccountReport -> linksAccountReport.get("next"))
            .map(HrefType::getHref)
            .ifPresent(
                // resolve all pages here
                nextLink -> {
                    try {
                        BookingsAndBalance bookingsAndBalance = resolve(nextLink, nextCallParams);
                        bookings.addAll(bookingsAndBalance.getBookings());
                        if (bookingsAndBalance.getClosingBookedBalance() != null) {
                            balancesReport.setReadyBalance(bookingsAndBalance.getClosingBookedBalance());
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException("Error resolving transactions page", e);
                    }
                }
            );

        // reverse order - last booking must be first in the list
        if (!bookings.isEmpty()) {
            LocalDate firstBookingDate = bookings.get(0).getBookingDate();
            LocalDate lastBookingDate = bookings.get(bookings.size() - 1).getBookingDate();

            if (firstBookingDate != null && lastBookingDate != null && firstBookingDate.compareTo(lastBookingDate) < 0) {
                Collections.reverse(bookings); // just switch order of bookings without changing siblings
            }
        }

        // calculate balance after transaction by subtracting the balance amounts from closing booked balance
        Optional.ofNullable(balancesReport.getReadyBalance()).ifPresent(
            closingBookedBalance -> {
                BigDecimal balance = closingBookedBalance.getAmount();
                for (Booking booking : bookings) {
                    booking.setBalance(balance);
                    booking.setExternalId(booking.getValutaDate() + "_" + booking.getAmount() + "_" + booking.getBalance()); // override fallback external id
                    balance = balance.subtract(booking.getAmount());
                }
                Optional.ofNullable(openingBookedBalance.getAmount()).ifPresent(
                    openingBookedAmount -> {
                        Booking firstBooking = bookings.get(bookings.size() - 1);
                        BigDecimal firstBookingBalance = firstBooking.getBalance();
                        BigDecimal balanceBeforeFirstBooking = firstBookingBalance.subtract(firstBooking.getAmount());
                        if (!openingBookedAmount.equals(balanceBeforeFirstBooking)) {
                            log.error("The opening booked balance {} and the calculated balance before the first transaction {} are not equal", openingBookedBalance, balanceBeforeFirstBooking);
                        }
                    }
                );
            }
        );

        return TransactionsResponse.builder()
            .bookings(bookings)
            .balancesReport(balancesReport)
            .build();
    }

    private BookingsAndBalance resolve(String nextLink, PaginationNextCallParameters nextCallParams) {
        List<Booking> bookings = new ArrayList<>();
        Balance closingBookedBalance = null;
        for (int i = 0; i < MAX_PAGES; i++) {
            BookingsAndBalance bookingsAndBalance = null;
            try {
                bookingsAndBalance = fetchNext(nextLink, nextCallParams);
            } catch (Exception e) {
                String message = e.getMessage();
                if (e instanceof ApiException) {
                    message = ((ApiException) e).getResponseBody();
                }
                log.error("Error fetching page " + i + ": " + message);
                log.error("We ignore this error and take what we got so far");
                break;
            }
            bookings.addAll(bookingsAndBalance.getBookings());
            if (bookingsAndBalance.getClosingBookedBalance() != null) {
                closingBookedBalance = bookingsAndBalance.getClosingBookedBalance();
            }
            if (bookingsAndBalance.getNextLink() != null) {
                nextLink = bookingsAndBalance.getNextLink();
            } else {
                break;
            }
        }
        return BookingsAndBalance.builder()
            .bookings(bookings)
            .closingBookedBalance(closingBookedBalance)
            .build();
    }

    private BookingsAndBalance fetchNext(String nextLink, PaginationNextCallParameters params) throws ApiException {
        String scrollRef = resolveScrollRef(nextLink);
        String page = resolvePage(nextLink);
        AccountInformationServiceAisApi aisApi = accountInformationServiceAisApi(xs2aAdapterBaseUrl,
            params.getBgSessionData());

        // Fiducia: "Only one of 'dateFrom' and 'scrollRef' may exist
        LocalDate dateFrom = null;
        LocalDate dateTo = null;

        // Commerzbank: Call must be equal
        if (scrollRef == null && page != null) {
            dateFrom = params.getDateFrom();
            dateTo = params.getDateTo();
        }

        Call aisCall = aisApi.getTransactionListCall(
            params.getResourceId(), "booked", UUID.randomUUID(),
            params.getConsentId(), null, params.getBankCode(), null, dateFrom,
            dateTo, null,
            null, params.isWithBalance(), null, null, null, null, null, null, null, null, null, null,
            null, null, null, scrollRef, page, null, null);

        ApiResponse<Object> apiResponse = aisApi.getApiClient().execute(aisCall, String.class);
        TransactionsResponse200Json transactionsResponse200JsonTO =
            GsonConfig.getGson().fromJson((String) apiResponse.getData(), TransactionsResponse200Json.class);

        List<Booking> bookings = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getBooked)
            .stream()
            .flatMap(Collection::stream)
            .map(bankingGatewayMapper::toBooking)
            .collect(Collectors.toList());

        Balance closingBookedBalance = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getBalances)
            .stream()
            .flatMap(Collection::stream)
            .filter(balance -> BalanceType.CLOSINGBOOKED.equals(balance.getBalanceType()))
            .map(bankingGatewayMapper::toBalance)
            .findFirst()
            .orElse(null);

        // Pagination. If another "next" link is present
        String next = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getLinks)
            .map(linksAccountReport -> linksAccountReport.get("next"))
            .map(HrefType::getHref)
            .orElse(null);

        return BookingsAndBalance.builder()
            .bookings(bookings)
            .closingBookedBalance(closingBookedBalance)
            .nextLink(next)
            .build();
    }

    // CAUTION scrollRef is not part of berlin group spec
    // it is used by Fiducia, but can be different at other banks
    private String resolveScrollRef(String nextLink) {
        MultiValueMap<String, String> parameters = UriComponentsBuilder.fromUriString(nextLink).build().getQueryParams();
        String scrollRefUrlEncoded = parameters.toSingleValueMap().get(FIDUCIA_PAGINATION_QUERY_PARAMETER);
        return scrollRefUrlEncoded != null ? URLDecoder.decode(scrollRefUrlEncoded, StandardCharsets.UTF_8) : null; // scroll ref contains special characters
    }

    private String resolvePage(String nextLink) {
        MultiValueMap<String, String> parameters = UriComponentsBuilder.fromUriString(nextLink).build().getQueryParams();
        String pageUrlEncoded = parameters.toSingleValueMap().get(COMMERZBANK_PAGINATION_QUERY_PARAMETER);
        return pageUrlEncoded != null ? URLDecoder.decode(pageUrlEncoded, StandardCharsets.UTF_8) : null; // scroll ref contains special characters
    }

    private BalanceList resolveBalanceListFromLink(PaginationNextCallParameters params, TransactionsResponse200Json transactionsResponse200JsonTO) {
        String balancesLink = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getLinks)
            .map(linksDownload -> linksDownload.get(BALANCES_LINK_KEY))
            .map(HrefType::getHref)
            .orElse(null);

        if (balancesLink == null) {
            return null;
        }

        List<String> pathSegments = UriComponentsBuilder.fromUriString(balancesLink).build().getPathSegments();
        int accountsIndex = pathSegments.lastIndexOf("accounts");
        if (accountsIndex == -1) {
            log.error("Balances link without accounts: " + balancesLink);
            return null;
        }
        String account = pathSegments.get(++accountsIndex);

        AccountInformationServiceAisApi aisApi = accountInformationServiceAisApi(xs2aAdapterBaseUrl, params.getBgSessionData());
        try {
            Call balanceCall = aisApi.getBalancesCall(account, UUID.randomUUID(), params.getConsentId(), null, params.getBankCode(), null, null,
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
            ApiResponse<ReadAccountBalanceResponse200> apiResponse = aisApi.getApiClient().execute(balanceCall, ReadAccountBalanceResponse200.class);
            if (apiResponse == null || apiResponse.getStatusCode() > 299) {
                log.error("Wrong status code on balance: " + (apiResponse != null ? apiResponse.getStatusCode() : ""));
            } else {
                return apiResponse.getData().getBalances();
            }
        } catch (Exception e) {
            log.error("Exception fetching balances for account: " + account, e);
        }
        return null;
    }

    @Data
    @Builder
    private static class BookingsAndBalance {
        private List<Booking> bookings;
        private Balance closingBookedBalance;
        private String nextLink;
    }

    @Data
    @Builder
    public static class PaginationNextCallParameters {
        private BgSessionData bgSessionData;
        private String resourceId;
        private String consentId;
        private String bankCode;
        private LocalDate dateFrom;
        private LocalDate dateTo;
        private boolean withBalance;
    }

    @Data
    @Builder
    static class AccountAndTransaction {
        private String account;
        private String transaction;
    }
}
