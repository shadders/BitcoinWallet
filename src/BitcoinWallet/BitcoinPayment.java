/**
 * Copyright 2014 Ronald W Hoffman
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package BitcoinWallet;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.bitcoin.protocols.payments.Protos;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;

import java.math.BigInteger;

import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;

import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXCertPathValidatorResult;
import java.security.cert.PKIXParameters;
import java.security.cert.X509Certificate;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;

import javax.security.auth.x500.X500Principal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A bitcoin payment request is created by a merchant when a customer purchases an item.  The merchant
 * sends the request URI to the browser, which invokes the program registered for the bitcoin URI.
 *
 * A BIP0021 payment request has the following format:
 *
 *     bitcoin:bitcoin-address?amount=nn.nn&label=sssss&message=sssss
 *
 *     The bitcoin-address and amount must be present, the other fields are optional.
 *
 * A BIP0070 payment request has the following format:
 *
 *     bitcoin:?r=request-url
 *
 *     The request URL is used to fetch the serialized payment request.  Other fields may be
 *     present in the bitcoin URI and will be ignored.
 *
 */
public class BitcoinPayment {

    /** Logger instance */
    private static final Logger log = LoggerFactory.getLogger(BitcoinPayment.class);

    /** Network */
    private String network = "main";

    /** Payment address */
    private Address address;

    /** Payment amount */
    private BigInteger amount = BigInteger.ZERO;

    /** Payment outputs */
    List<TransactionOutput> outputList;

    /** Merchant name */
    private String merchantName = "<Unknown>";

    /** Merchant public key */
    PublicKey pubKey;

    /** Payment request memo */
    private String requestMemo = "";

    /** Merchant data */
    private ByteString merchantData;

    /** Payment URL */
    private URL paymentURL;

    /** Payment acknowledgment memo */
    private String ackMemo = "";

    /**
     * Processes a payment request
     *
     * @param       bitcoinURI                  The bitcoin URI
     * @throws      BitcoinPaymentException     Unable to process the payment request
     */
    public BitcoinPayment(BitcoinURI bitcoinURI) throws BitcoinPaymentException {
        URL requestURL = bitcoinURI.getRequestURL();
        if (requestURL == null) {
            //
            // For a BIP0021 payment request, all of the information is contained in the URI
            //
            this.address = bitcoinURI.getAddress();
            this.amount = bitcoinURI.getAmount();
            this.merchantName = bitcoinURI.getName();
            this.requestMemo = bitcoinURI.getMessage();
        } else {
            try {
                //
                // For a BIP0070 payment request, we need to fetch the request from the merchant
                //
                Protos.PaymentRequest request;
                Protos.PaymentDetails details;
                log.info(String.format("Request URL: %s", requestURL.toString()));
                HttpURLConnection conn = (HttpURLConnection)requestURL.openConnection();
                conn.setRequestMethod("GET");
                conn.setDoInput(true);
                conn.setRequestProperty("Accept", "application/bitcoin-paymentrequest");
                conn.setUseCaches(false);
                try (InputStream inStream = conn.getInputStream()) {
                    request = Protos.PaymentRequest.parseFrom(inStream);
                    int responseCode = conn.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK)
                        throw new BitcoinPaymentException(
                                String.format("Unable to get payment request: HTTP %d\n  %s",
                                              responseCode, conn.getResponseMessage()));
                }
                if (!request.hasSerializedPaymentDetails())
                    throw new BitcoinPaymentException("No payment details included in payment request");
                details = Protos.PaymentDetails.parseFrom(request.getSerializedPaymentDetails());
                if (details.getOutputsCount() == 0)
                    throw new BitcoinPaymentException("No payment outputs in payment request");
                if (details.hasExpires()) {
                    if (details.getExpires() < System.currentTimeMillis()/1000)
                        throw new BitcoinPaymentException("Payment request has expired");
                }
                //
                // Save the payment details
                //
                if (details.hasNetwork())
                    network = details.getNetwork();
                if (details.hasMemo())
                    requestMemo = details.getMemo();
                if (details.hasMerchantData())
                    merchantData = details.getMerchantData();
                if (details.hasPaymentUrl()) {
                    String urlString = details.getPaymentUrl();
                    log.info(String.format("Payment URL: %s", urlString));
                    paymentURL = new URL(urlString);
                }
                //
                // Build the transaction output list
                //
                int outputCount = details.getOutputsCount();
                log.info(String.format("Output count is %d", outputCount));
                outputList = new ArrayList<>(outputCount);
                for (int i=0; i<outputCount; i++) {
                    Protos.Output output = details.getOutputs(i);
                    BigInteger outputAmount;
                    if (output.hasAmount())
                        outputAmount = BigInteger.valueOf(output.getAmount());
                    else
                        outputAmount = BigInteger.ZERO;
                    amount = amount.add(outputAmount);
                    if (!output.hasScript())
                        throw new BitcoinPaymentException("No script provided for payment output");
                    byte[] scriptBytes = output.getScript().toByteArray();
                    outputList.add(new TransactionOutput(i, outputAmount, scriptBytes));
                    if (address == null && scriptBytes.length == 25 &&
                                           scriptBytes[0] == (byte)ScriptOpCodes.OP_DUP &&
                                           scriptBytes[1] == (byte)ScriptOpCodes.OP_HASH160 &&
                                           scriptBytes[2] == 20 &&
                                           scriptBytes[23] == (byte)ScriptOpCodes.OP_EQUALVERIFY &&
                                           scriptBytes[24] == (byte)ScriptOpCodes.OP_CHECKSIG)
                        address = new Address(Arrays.copyOfRange(scriptBytes, 3, 23));
                }
                //
                // Validate the X.509 certificates
                //
                if (request.hasPkiType()) {
                    String pkiType = request.getPkiType();
                    String algorithm;
                    if (!pkiType.equals("none") && request.hasPkiData()) {
                        switch (pkiType) {
                            case "x509+sha256":
                                algorithm = "SHA256withRSA";
                                break;
                            case "x509+sha1":
                                algorithm = "SHA1withRSA";
                                break;
                            default:
                                throw new BitcoinPaymentException(
                                        String.format("Unrecognized PKI algorithm type: %s", pkiType));
                        }
                        Protos.X509Certificates certificates =
                                        Protos.X509Certificates.parseFrom(request.getPkiData());
                        if (certificates.getCertificateCount() == 0)
                            throw new BitcoinPaymentException("No X.509 certificates provided");
                        verifyCertificates(algorithm, certificates, request);
                    }
                }
            } catch (InvalidProtocolBufferException exc) {
                log.error("Invalid protocol buffer", exc);
                throw new BitcoinPaymentException("Invalid protocol buffer in payment request");
            } catch (MalformedURLException exc) {
                log.error("Malformed payment URL", exc);
                throw new BitcoinPaymentException("Malformed payment URL in payment request");
            } catch (IOException exc) {
                log.error("Unable to get payment request", exc);
                throw new BitcoinPaymentException(String.format("Unable to get payment request:\n  %s",
                                                  exc.getMessage()));
            }
        }
    }

    /**
     * Sends coins to the supplied payment address
     *
     * @throws      BitcoinPaymentException     Unable to send the payment
     * @throws      InsufficientFeeException    Not enough coins available
     */
    public void sendCoins() throws BitcoinPaymentException, InsufficientFeeException {
        try {
            //
            // Get the list of available inputs
            //
            List<SignedInput> inputList = Utils.buildSignedInputs();
            //
            // Build the new transaction
            //
            Transaction tx;
            BigInteger sendFee = Parameters.MIN_TX_FEE;
            while (true) {
                BigInteger totalAmount = amount.add(sendFee);
                List<SignedInput> inputs = new ArrayList<>(inputList.size());
                for (SignedInput input : inputList) {
                    inputs.add(input);
                    totalAmount = totalAmount.subtract(input.getValue());
                    if (totalAmount.signum() <= 0)
                        break;
                }
                if (totalAmount.signum() > 0)
                    throw new InsufficientFeeException("Insufficient coins available");
                List<TransactionOutput> outputs;
                if (outputList != null) {
                    outputs = new ArrayList<>(outputList.size()+1);
                    outputs.addAll(outputList);
                } else {
                    outputs = new ArrayList<>(2);
                    outputs.add(new TransactionOutput(0, amount, address));
                }
                BigInteger change = totalAmount.negate();
                if (change.compareTo(Parameters.DUST_TRANSACTION) > 0)
                    outputs.add(new TransactionOutput(outputs.size(), change, Parameters.changeKey.toAddress()));
                //
                // Create the new transaction using the supplied inputs and outputs
                //
                tx = new Transaction(inputs, outputs);
                //
                // The minimum fee increases for every 1000 bytes of serialized transaction data.  We
                // will need to increase the send fee if it doesn't cover the minimum fee.
                //
                int length = tx.getBytes().length;
                BigInteger minFee = BigInteger.valueOf(length/1000+1).multiply(Parameters.MIN_TX_FEE);
                if (minFee.compareTo(sendFee) <= 0)
                    break;
                sendFee = minFee;
            }
            //
            // BIP0021 request: Store the new transaction in the database and broadcast it to our peers
            // BIP0070 request: Send the payment to the merchant and get the acknowledgement before
            //                  broadcasting the transaction
            //
            if (paymentURL != null)
                sendPayment(tx);
            Parameters.databaseHandler.processTransaction(tx);
            List<Sha256Hash> invList = new ArrayList<>(2);
            invList.add(tx.getHash());
            Message invMsg = InventoryMessage.buildInventoryMessage(null, Parameters.INV_TX, invList);
            Parameters.networkHandler.broadcastMessage(invMsg);
            //
            // Create a send address for the merchant
            //
            if (address != null && merchantName.length() != 0 && !Parameters.addresses.contains(address)) {
                String label = merchantName;
                synchronized(Parameters.lock) {
                    //
                    // Check for a duplicate label
                    //
                    boolean valid = false;
                    int length = label.length();
                    int dupCount = 0;
                    while (!valid) {
                        valid = true;
                        for (int i=0; i<Parameters.addresses.size(); i++) {
                            Address chkAddr = Parameters.addresses.get(i);
                            if (chkAddr.getLabel().compareToIgnoreCase(label) == 0) {
                                label = label.substring(0, length)+String.format("(%d)", ++dupCount);
                                valid = false;
                                break;
                            }
                        }
                    }
                    //
                    // Insert the address in the list sorted by label
                    //
                    address.setLabel(label);
                    boolean added = false;
                    for (int i=0; i<Parameters.addresses.size(); i++) {
                        Address chkAddr = Parameters.addresses.get(i);
                        if (chkAddr.getLabel().compareToIgnoreCase(label) > 0) {
                            Parameters.addresses.add(i, address);
                            added = true;
                            break;
                        }
                    }
                    if (!added) {
                        Parameters.addresses.add(address);
                    }
                }
                //
                // Store the address in the database
                //
                Parameters.wallet.storeAddress(address);
            }
        } catch (WalletException exc) {
            log.error("Unable to build transaction inputs", exc);
            throw new BitcoinPaymentException("Unable to build transaction inputs");
        }
    }

    /**
     * Sends the payment to the merchant and receive the acknowledgement
     *
     * @param       tx                          Generated transaction
     * @throws      BitcoinPaymentException     Unable to send payment
     */
    private void sendPayment(Transaction tx) throws BitcoinPaymentException {
        //
        // Create the payment
        //
        Protos.Payment.Builder paymentBuilder = Protos.Payment.newBuilder();
        if (merchantData != null)
            paymentBuilder.setMerchantData(merchantData);
        StringBuilder stringBuilder = new StringBuilder(256);
        stringBuilder.append("Payment of ").append(Main.satoshiToString(amount)).append(" BTC");
        stringBuilder.append(" to ").append(merchantName);
        if (requestMemo.length() > 0)
            stringBuilder.append(" for ").append(requestMemo);
        paymentBuilder.setMemo(stringBuilder.toString());
        paymentBuilder.addTransactions(ByteString.copyFrom(tx.getBytes()));
        Protos.Payment payment = paymentBuilder.build();
        //
        // Send the payment to the merchant and get the response
        //
        try {
            HttpURLConnection conn = (HttpURLConnection)paymentURL.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/bitcoin-payment");
            conn.setRequestProperty("Accept", "application/bitcoin-paymentack");
            conn.setRequestProperty("Content-Length", Integer.toString(payment.getSerializedSize()));
            conn.setUseCaches(false);
            conn.setDoInput(true);
            conn.setDoOutput(true);
            try (DataOutputStream outStream = new DataOutputStream(conn.getOutputStream())) {
                payment.writeTo(outStream);
                outStream.flush();
                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK)
                    throw new BitcoinPaymentException(
                            String.format("Unable to send payment to merchant: HTTP %d\n  %s",
                                          responseCode, conn.getResponseMessage()));
            }
            try (InputStream inStream = conn.getInputStream()) {
                Protos.PaymentACK paymentACK = Protos.PaymentACK.parseFrom(inStream);
                int responseCode = conn.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK)
                    throw new BitcoinPaymentException(
                            String.format("Unable to read acknowledgement from merchant: HTTP %d\n  %s",
                                          responseCode, conn.getResponseMessage()));
                if (paymentACK.hasMemo())
                    ackMemo = paymentACK.getMemo();
            }
        } catch (IOException exc) {
            log.error("Unable to send payment and get acknowledgement", exc);
            throw new BitcoinPaymentException(
                                String.format("Unable to send payment and get acknowledgement\n  %s",
                                exc.getMessage()));
        }
    }

    /**
     * Validates the X.509 certificates included in the payment request
     *
     * @param       algorithm                   PKI algorithm
     * @param       certificates                X.509 certificates
     * @param       request                     Payment request
     * @throws      BitcoinPaymentException     Invalid certificate
     */
    private void verifyCertificates(String algorithm, Protos.X509Certificates certificates,
                                    Protos.PaymentRequest request) throws BitcoinPaymentException {
        try {
            //
            // Get the certificate chain.  The CertificateFactory supports DER and Base64
            // encodings.
            //
            CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> certs = new ArrayList<>(certificates.getCertificateCount());
            for (ByteString bytes : certificates.getCertificateList())
                certs.add((X509Certificate)certificateFactory.generateCertificate(bytes.newInput()));
            CertPath certPath = certificateFactory.generateCertPath(certs);
            //
            // Create the PKIX parameters using the trusted keystore
            //
            PKIXParameters params = new PKIXParameters(createKeyStore());
            params.setRevocationEnabled(false);
            //
            // Verify the certificate chain
            //
            CertPathValidator  validator = CertPathValidator.getInstance("PKIX");
            PKIXCertPathValidatorResult result = (PKIXCertPathValidatorResult)validator.validate(certPath, params);
            //
            // Verify the request signature
            //
            pubKey = result.getPublicKey();
            Signature sig = Signature.getInstance(algorithm);
            sig.initVerify(pubKey);
            Protos.PaymentRequest.Builder chkRequest = request.toBuilder();
            chkRequest.setSignature(ByteString.EMPTY);
            sig.update(chkRequest.build().toByteArray());
            if (!sig.verify(request.getSignature().toByteArray()))
                throw new BitcoinPaymentException("Payment request signature is incorrect");
            X500Principal principal = certs.get(0).getSubjectX500Principal();
            merchantName = principal.getName("RFC2253");
        } catch (InvalidAlgorithmParameterException | NoSuchAlgorithmException exc) {
            log.error("Unsupported PKIX algorithm", exc);
            throw new BitcoinPaymentException("Unsupported PKIX algorithm in payment request certificate");
        } catch (InvalidKeyException exc) {
            log.error("Invalid certificate public key", exc);
            throw new BitcoinPaymentException("Invalid certificate public key");
        } catch (CertificateException | CertPathValidatorException exc) {
            log.error("Certification validation failed", exc);
            throw new BitcoinPaymentException("Certificate validation failed for payment request");
        } catch (SignatureException exc) {
            log.error("Unable to verify payment request signature", exc);
            throw new BitcoinPaymentException("Unable to verify payment request signature");
        } catch (KeyStoreException exc) {
            log.error("Unable to access keystore", exc);
            throw new BitcoinPaymentException("Unable to access keystore");
        }
    }

    /**
     * Creates the key store
     *
     * @throws      BitcoinPaymentException     Unable to create key store
     */
    private KeyStore createKeyStore() throws BitcoinPaymentException {
        KeyStore keyStore;
        try {
            File file = null;
            String pw = null;
            // Try the JSSE keystore
            String path = System.getProperty("javax.net.ssl.trustStore");
            if (path != null) {
                path = path.replace("/", Main.fileSeparator);
                file = new File(path);
                if (file.exists())
                    pw = System.getProperty("javax.net.ssl.trustStorePassword");
                else
                    path = null;
            }
            // Try the default user keystore
            if (path == null) {
                path = System.getProperty("user.home")+Main.fileSeparator+".keystore";
                file = new File(path.replace("/", Main.fileSeparator));
                if (!file.exists())
                    path = null;
            }
            // Try the Java runtime keystore
            if (path == null) {
                path = System.getProperty("java.home")+"/lib/security/cacerts".replace("/", Main.fileSeparator);
                file = new File(path.replace("/", Main.fileSeparator));
                if (!file.exists())
                    throw new BitcoinPaymentException("No key store available");
            }
            // Load the keystore
            if (pw == null)
                pw = "changeit";
            log.info(String.format("KeyStore path: %s", path));
            keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            try (FileInputStream inStream = new FileInputStream(file)) {
                keyStore.load(inStream, pw.toCharArray());
            }
        } catch (NoSuchAlgorithmException | CertificateException | KeyStoreException | IOException exc) {
            log.error("Unable to create keystore", exc);
            throw new BitcoinPaymentException("Unable to create keystore");
        }
        return keyStore;
    }

    /**
     * Returns the bitcoin network: "main" for the production network or "test" for the test network
     *
     * @return                          Bitcoin network
     */
    public String getNetwork() {
        return network;
    }

    /**
     * Returns the merchant name
     *
     * @return                          Merchant name
     */
    public String getMerchantName() {
        return merchantName;
    }

    /**
     * Returns the payment amount
     *
     * @return                          Payment amount
     */
    public BigInteger getAmount() {
        return amount;
    }

    /**
     * Returns the request memo
     *
     * @return                          Memo
     */
    public String getRequestMemo() {
        return requestMemo;
    }

    /**
     * Returns the acknowledgment memo
     *
     * @return                          Memo
     */
    public String getAckMemo() {
        return ackMemo;
    }
}
