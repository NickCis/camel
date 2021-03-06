/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.component.salesforce;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.camel.util.ObjectHelper;
import org.junit.Assert;

public class LoginConfigHelper extends Assert {

    protected static final String TEST_LOGIN_PROPERTIES = "../test-salesforce-login.properties";

    public static SalesforceLoginConfig getLoginConfig() throws IOException {

        // load test-salesforce-login properties
        Properties properties = new Properties();
        InputStream stream = null;
        try {
            final SalesforceLoginConfig config;
            stream = new FileInputStream(TEST_LOGIN_PROPERTIES);
            properties.load(stream);

            if (ObjectHelper.isEmpty(properties.getProperty("refreshToken"))) {
                config = new SalesforceLoginConfig(
                        properties.getProperty("loginUrl", SalesforceLoginConfig.DEFAULT_LOGIN_URL),
                        properties.getProperty("clientId"),
                        properties.getProperty("clientSecret"),
                        properties.getProperty("userName"),
                        properties.getProperty("password"),
                        Boolean.parseBoolean(properties.getProperty("lazyLogin", "false")));
            } else {
                config = new SalesforceLoginConfig(
                        properties.getProperty("loginUrl", SalesforceLoginConfig.DEFAULT_LOGIN_URL),
                        properties.getProperty("clientId"), //
                        properties.getProperty("clientSecret"), //
                        properties.getProperty("refreshToken"), //
                        Boolean.parseBoolean(properties.getProperty("lazyLogin", "false")));
            }


            assertNotNull("Null loginUrl", config.getLoginUrl());
            assertNotNull("Null clientId", config.getClientId());
            assertNotNull("Null clientSecret", config.getClientSecret());
            if (ObjectHelper.isEmpty(properties.getProperty("refreshToken"))) {
                assertNotNull("Null userName", config.getUserName());
                assertNotNull("Null password", config.getPassword());
            } else {
                assertNotNull("Null refreshToken", config.getRefreshToken());
            }


            return config;

        } catch (FileNotFoundException e) {
            throw new FileNotFoundException("Create a properties file named "
                + TEST_LOGIN_PROPERTIES + " with clientId, clientSecret, userName, and password or with clientId, clientSecret and refreshToken"
                + " for a Salesforce account with Merchandise and Invoice objects from Salesforce Guides.");
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

}