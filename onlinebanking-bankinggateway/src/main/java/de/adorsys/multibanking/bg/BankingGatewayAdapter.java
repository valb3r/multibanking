package de.adorsys.multibanking.bg;

import com.squareup.okhttp.Call;
import de.adorsys.multibanking.domain.*;
import de.adorsys.multibanking.domain.exception.MultibankingError;
import de.adorsys.multibanking.domain.exception.MultibankingException;
import de.adorsys.multibanking.domain.request.TransactionRequest;
import de.adorsys.multibanking.domain.response.*;
import de.adorsys.multibanking.domain.spi.OnlineBankingService;
import de.adorsys.multibanking.domain.spi.StrongCustomerAuthorisable;
import de.adorsys.multibanking.domain.transaction.*;
import de.adorsys.multibanking.mapper.TransactionsParser;
import de.adorsys.multibanking.xs2a_adapter.ApiException;
import de.adorsys.multibanking.xs2a_adapter.ApiResponse;
import de.adorsys.multibanking.xs2a_adapter.api.AccountInformationServiceAisApi;
import de.adorsys.multibanking.xs2a_adapter.model.*;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static de.adorsys.multibanking.bg.ApiClientFactory.accountInformationServiceAisApi;
import static de.adorsys.multibanking.domain.BankApi.XS2A;
import static de.adorsys.multibanking.domain.exception.MultibankingError.*;

@Slf4j
public class BankingGatewayAdapter implements OnlineBankingService {

    private final BankingGatewayScaHandler scaHandler;
    private final String xs2aAdapterBaseUrl;

    private BankingGatewayMapper bankingGatewayMapper = new BankingGatewayMapperImpl();

    public BankingGatewayAdapter(String bankingGatewayBaseUrl, String xs2aAdapterBaseUrl) {
        this.scaHandler = new BankingGatewayScaHandler(bankingGatewayBaseUrl);
        this.xs2aAdapterBaseUrl = xs2aAdapterBaseUrl;
    }

    @Override
    public BankApi bankApi() {
        return XS2A;
    }

    @Override
    public boolean externalBankAccountRequired() {
        return false;
    }

    @Override
    public boolean userRegistrationRequired() {
        return true;
    }

    @Override
    public BankApiUser registerUser(String userId) {
        BankApiUser bankApiUser = new BankApiUser();
        bankApiUser.setBankApi(bankApi());
        return bankApiUser;
    }

    @Override
    public void removeUser(BankApiUser bankApiUser) {
        //noop
    }

    @Override
    public AccountInformationResponse loadBankAccounts(TransactionRequest<LoadAccounts> loadAccountInformationRequest) {
        try {
            String bankCode = loadAccountInformationRequest.getBank().getBankApiBankCode() != null
                ? loadAccountInformationRequest.getBank().getBankApiBankCode()
                : loadAccountInformationRequest.getBankAccess().getBankCode();
            BgSessionData bgSessionData = (BgSessionData) loadAccountInformationRequest.getBankApiConsentData();

            AccountList accountList = getAccountList(bgSessionData, bankCode,
                loadAccountInformationRequest.getBankAccess().getConsentId(), false);

            List<BankAccount> bankAccounts = bankingGatewayMapper.toBankAccounts(accountList.getAccounts());

            return AccountInformationResponse.builder()
                .bankAccess(loadAccountInformationRequest.getBankAccess())
                .bankAccounts(bankAccounts)
                .build();
        } catch (ApiException e) {
            throw handeAisApiException(e);
        }
    }

    private AccountList getAccountList(BgSessionData bgSessionData, String bankCode, String consentId,
                                       boolean withBalance) throws ApiException {
        AccountInformationServiceAisApi aisApi = accountInformationServiceAisApi(xs2aAdapterBaseUrl, bgSessionData);

        return aisApi.getAccountList(UUID.randomUUID(), consentId, null, bankCode, null, withBalance, null, null,
            null, null,
            null, null, null, null, null, null, null, null, null);
    }

    @Override
    public void removeBankAccount(BankAccount bankAccount, BankApiUser bankApiUser) {
        //noop
    }

    public TransactionsResponse loadTransactions(TransactionRequest<LoadTransactions> loadTransactionsRequest) {
        LoadTransactions loadTransactions = loadTransactionsRequest.getTransaction();
        LocalDate dateFrom = loadTransactions.getDateFrom() != null ? loadTransactions.getDateFrom() :
            LocalDate.now().minusYears(1);

        String bankCode = loadTransactionsRequest.getBank().getBankApiBankCode() != null
            ? loadTransactionsRequest.getBank().getBankApiBankCode()
            : loadTransactionsRequest.getBankAccess().getBankCode();
        BgSessionData bgSessionData = (BgSessionData) loadTransactionsRequest.getBankApiConsentData();

        try {
            String resourceId = Optional.ofNullable(loadTransactions.getPsuAccount().getExternalIdMap().get(bankApi()))
                .orElseGet(() -> getAccountResourceId(bgSessionData,
                    loadTransactionsRequest.getBankAccess().getIban(), bankCode,
                    loadTransactionsRequest.getBankAccess().getConsentId()));

            AccountInformationServiceAisApi aisApi = accountInformationServiceAisApi(xs2aAdapterBaseUrl,
                bgSessionData);

            Call aisCall = aisApi.getTransactionListCall(
                resourceId, "booked", UUID.randomUUID(),
                loadTransactionsRequest.getBankAccess().getConsentId(), null, bankCode, null, dateFrom,
                loadTransactions.getDateTo(), null,
                null, loadTransactions.isWithBalance(), null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);

            ApiResponse<Object> apiResponse = aisApi.getApiClient().execute(aisCall, String.class);
            String contentType = apiResponse.getHeaders().keySet().stream()
                .filter(header -> header.toLowerCase().contains("content-type"))
                .findFirst()
                .orElse("");

            if (contentType.toLowerCase().contains("application/xml")) {
                return TransactionsParser.camtStringToLoadBookingsResponse((String) apiResponse.getData());
            } else if (contentType.toLowerCase().contains("text/plain")) {
                return TransactionsParser.mt940StringToLoadBookingsResponse((String) apiResponse.getData());
            } else {
                return jsonStringToLoadBookingsResponse((String) apiResponse.getData());
            }
        } catch (ApiException e) {
            throw handeAisApiException(e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            throw new MultibankingException(INTERNAL_ERROR, 500, "Error loading bookings: " + e.getMessage());
        }
    }

    @Override
    public StandingOrdersResponse loadStandingOrders(TransactionRequest<LoadStandingOrders> loadStandingOrdersRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public LoadBalancesResponse loadBalances(TransactionRequest<LoadBalances> request) {
        throw new UnsupportedOperationException();
    }

    private TransactionsResponse jsonStringToLoadBookingsResponse(String json) throws IOException {
        TransactionsResponse200Json transactionsResponse200JsonTO =
            ObjectMapperConfig.getObjectMapper().readValue(json, TransactionsResponse200Json.class);
        List<Booking> bookings = Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getTransactions)
            .map(AccountReport::getBooked)
            .map(transactions -> bankingGatewayMapper.toBookings(transactions))
            .orElse(Collections.emptyList());

        BalancesReport balancesReport = new BalancesReport();
        Optional.ofNullable(transactionsResponse200JsonTO)
            .map(TransactionsResponse200Json::getBalances)
            .orElse(new BalanceList())
            .forEach(balance -> Optional.ofNullable(balance.getBalanceType())
                .ifPresent(balanceType -> {
                    switch (balance.getBalanceType()) {
                        case EXPECTED:
                            balancesReport.setUnreadyBalance(bankingGatewayMapper.toBalance(balance));
                            break;
                        case CLOSINGBOOKED:
                            balancesReport.setReadyBalance(bankingGatewayMapper.toBalance(balance));
                            break;
                        default:
                            // ignore
                            break;
                    }
                }));

        return TransactionsResponse.builder()
            .bookings(bookings)
            .balancesReport(balancesReport)
            .build();
    }

    private String getAccountResourceId(BgSessionData bgSessionData, String iban, String bankCode, String consentId) {
        try {
            AccountList accountList = getAccountList(bgSessionData, bankCode, consentId, false);

            return accountList.getAccounts()
                .stream()
                .filter(accountDetails -> accountDetails.getIban().equals(iban))
                .findAny()
                .map(AccountDetails::getResourceId)
                .orElseThrow(() -> new MultibankingException(INVALID_ACCOUNT_REFERENCE));
        } catch (ApiException e) {
            throw handeAisApiException(e);
        }
    }

    @Override
    public boolean bankSupported(String bankCode) {
        return true;
    }

    @Override
    public boolean bookingsCategorized() {
        return false;
    }

    @Override
    public PaymentResponse executePayment(TransactionRequest<? extends AbstractPayment> paymentRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public StrongCustomerAuthorisable getStrongCustomerAuthorisation() {
        return scaHandler;
    }

    private MultibankingException handeAisApiException(ApiException e) {
        switch (e.getCode()) {
            case 401:
                return toMultibankingException(e, INVALID_PIN);
            case 404:
                return toMultibankingException(e, RESOURCE_NOT_FOUND);
            case 429:
                return new MultibankingException(INVALID_CONSENT, 429, "consent access exceeded");
            default:
                return toMultibankingException(e, BANKING_GATEWAY_ERROR);
        }
    }

    private MultibankingException toMultibankingException(ApiException e, MultibankingError multibankingError) {
        try {
            Error400NGAIS messagesTO = ObjectMapperConfig.getObjectMapper().readValue(e.getResponseBody(),
                Error400NGAIS.class);
            return new MultibankingException(multibankingError, e.getCode(),
                bankingGatewayMapper.toMessages(messagesTO.getTppMessages()));
        } catch (Exception e2) {
            return new MultibankingException(multibankingError, 500, e.getMessage());
        }
    }

}

