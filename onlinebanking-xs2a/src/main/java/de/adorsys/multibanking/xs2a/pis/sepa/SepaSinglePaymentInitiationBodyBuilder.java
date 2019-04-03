/*
 * Copyright 2018-2018 adorsys GmbH & Co KG
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.adorsys.multibanking.xs2a.pis.sepa;

import de.adorsys.multibanking.domain.AbstractScaTransaction;
import de.adorsys.multibanking.domain.SinglePayment;
import de.adorsys.psd2.client.model.PaymentInitiationSctJson;

public class SepaSinglePaymentInitiationBodyBuilder extends AbstractPaymentInitiationBodyBuilder<PaymentInitiationSctJson> {

    @Override
    public PaymentInitiationSctJson buildBody(AbstractScaTransaction transaction) {
        return buildPaymentInitiation((SinglePayment) transaction);
    }

    private PaymentInitiationSctJson buildPaymentInitiation(SinglePayment paymentBodyObj) {
        PaymentInitiationSctJson paymentInitiation = new PaymentInitiationSctJson();
        paymentInitiation.setDebtorAccount(buildDebtorAccountReference(paymentBodyObj));
        paymentInitiation.setCreditorAccount(buildCreditorAccountReference(paymentBodyObj));
        paymentInitiation.setInstructedAmount(buildAmount(paymentBodyObj));
        paymentInitiation.setCreditorName(paymentBodyObj.getReceiver());
        paymentInitiation.setRemittanceInformationUnstructured(paymentBodyObj.getPurpose());
        return paymentInitiation;
    }

}
