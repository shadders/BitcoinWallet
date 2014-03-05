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

import java.io.UnsupportedEncodingException;

import java.math.BigDecimal;
import java.math.BigInteger;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;

/**
 * A bitcoin URI has the format bitcoin:address?param1&param2&param3...
 *
 * The following parameters are defined by BIP0021 and BIP0070
 *
 *   amount         The BTC amount expressed as a decimal number (1.00 = 1 BTC)
 *   label          Merchant identification
 *   message        A message to be displayed to the user
 *   r              Payment request URL
 */
public class BitcoinURI {

    /** Payment address */
    private Address address;

    /** Payment amount */
    private BigInteger amount = BigInteger.ZERO;

    /** Merchant name */
    private String name = "<Unknown>";

    /** Request message */
    private String message = "";

    /** Request URL */
    private URL requestURL;

    /**
     * Creates a new Bitcoin URI
     *
     * @param       uriString               Encoded URI string
     * @throws      AddressFormatException  The bitcoin address is not valid
     * @throws      BitcoinURIException     The URI syntax is not valid
     */
    public BitcoinURI(String uriString) throws AddressFormatException, BitcoinURIException {
        //
        // A bitcoin URI starts with "bitcoin:"
        //
        if (!uriString.startsWith("bitcoin:"))
            throw new BitcoinURIException("URI is not a bitcoin URI");
        //
        // Get the address
        //
        int start = 8;
        int sep = uriString.indexOf('?');
        if (sep < start)
            throw new BitcoinURIException("Invalid bitcoin URI syntax");
        if (sep-start > 0)
            address = new Address(uriString.substring(start, sep));
        //
        // Get the parameters (ignore unrecognized parameters to allow future extensions)
        //
        try {
            if (++sep >= uriString.length())
                throw new BitcoinURIException("Invalid bitcoin URI syntax");
            String[] uriParams = uriString.substring(sep).split("&");
            for (String uriParam : uriParams) {
                sep = uriParam.indexOf('=');
                if (sep < 1)
                    throw new BitcoinURIException("Invalid bitcoin URI syntax");
                String pName = uriParam.substring(0, sep);
                String pValue = URLDecoder.decode(uriParam.substring(sep+1), "UTF-8");
                switch (pName) {
                    case "amount":
                        amount = new BigDecimal(pValue).movePointRight(8).toBigInteger();
                        break;
                    case "label":
                        name = pValue;
                        break;
                    case "message":
                        message = pValue;
                        break;
                    case "r":
                        requestURL = new URL(pValue);
                        break;
                }
            }
        } catch (MalformedURLException exc) {
            throw new BitcoinURIException("Malformed payment request URL", exc);
        } catch (UnsupportedEncodingException exc) {
            throw new BitcoinURIException("Unsupported URI encoding", exc);
        }
    }

    /**
    * Returns the bitcoin payment address
    *
    * @return                          Payment address or null
    */
    public Address getAddress() {
        return address;
    }

    /**
     * Returns the merchant name
     *
     * @return                          Merchant name
     */
    public String getName() {
        return name;
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
     * Returns the message
     *
     * @return                          Message
     */
    public String getMessage() {
        return message;
    }

    /**
     * Returns the payment request URL
     *
     * @return                          Payment request URL or null
     */
    public URL getRequestURL() {
        return requestURL;
    }
}