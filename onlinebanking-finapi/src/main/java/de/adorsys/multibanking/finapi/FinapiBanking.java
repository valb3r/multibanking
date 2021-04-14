package de.adorsys.multibanking.finapi;

import de.adorsys.multibanking.domain.BankAccount;
import de.adorsys.multibanking.domain.BankApi;
import de.adorsys.multibanking.domain.BankApiUser;
import de.adorsys.multibanking.domain.request.TransactionRequest;
import de.adorsys.multibanking.domain.response.*;
import de.adorsys.multibanking.domain.spi.OnlineBankingService;
import de.adorsys.multibanking.domain.spi.StrongCustomerAuthorisable;
import de.adorsys.multibanking.domain.transaction.*;
import org.adorsys.envutils.EnvProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.SecureRandom;
import java.time.format.DateTimeFormatter;

import static de.adorsys.multibanking.domain.utils.Utils.getSecureRandom;

/**
 * Created by alexg on 17.05.17.
 */
public class FinapiBanking implements OnlineBankingService {

    //https://finapi.zendesk.com/hc/en-us/articles/222013148
    private static final String CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789&(){}[]" +
        ".:,?!+-_$@#";
    private static final Logger LOG = LoggerFactory.getLogger(FinapiBanking.class);
    private static SecureRandom random = getSecureRandom();
    private DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private String finapiClientId;
    private String finapiSecret;
    private String finapiConnectionUrl;

    public FinapiBanking() {
        finapiClientId = EnvProperties.getEnvOrSysProp("FINAPI_CLIENT_ID", true);
        finapiSecret = EnvProperties.getEnvOrSysProp("FINAPI_SECRET", true);
        finapiConnectionUrl = EnvProperties.getEnvOrSysProp("FINAPI_CONNECTION_URL", "https://sandbox.finapi.io/");

        if (finapiClientId == null || finapiSecret == null) {
            LOG.warn("missing env properties FINAPI_CLIENT_ID and/or FINAPI_SECRET");
        } else {
        }
    }

    @Override
    public BankApi bankApi() {
        return BankApi.FINAPI;
    }

    @Override
    public boolean externalBankAccountRequired() {
        return true;
    }

    @Override
    public boolean userRegistrationRequired() {
        return true;
    }

    @Override
    public boolean bankSupported(String bankCode) {
        return true;
    }

    @Override
    public BankApiUser registerUser(String userId) {
        return null;
    }

    @Override
    public void removeUser(BankApiUser bankApiUser) {
    }

    @Override
    public AccountInformationResponse loadBankAccounts(TransactionRequest<LoadAccounts> loadAccountInformationRequest) {
        return null;
    }

    @Override
    public void removeBankAccount(BankAccount bankAccount, BankApiUser bankApiUser) {
    }

    @Override
    public TransactionsResponse loadTransactions(TransactionRequest<LoadTransactions> loadTransactionsRequest) {
        return null;
    }

    @Override
    public StandingOrdersResponse loadStandingOrders(TransactionRequest<LoadStandingOrders> loadStandingOrdersRequest) {
        throw new UnsupportedOperationException();
    }

    @Override
    public LoadBalancesResponse loadBalances(TransactionRequest<LoadBalances> request) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean bookingsCategorized() {
        return true;
    }

    @Override
    public PaymentResponse executePayment(TransactionRequest<? extends AbstractPayment> paymentRequest) {
        return null;
    }

    @Override
    public StrongCustomerAuthorisable getStrongCustomerAuthorisation() {
        throw new UnsupportedOperationException();
    }
}
