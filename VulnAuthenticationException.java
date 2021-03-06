/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.shield.authc;

import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.shield.ShieldException;

/**
 *
 */
public class AuthenticationException extends ShieldException {

    public AuthenticationException(String msg) {
        super(msg);
    }

    public AuthenticationException(String msg, Throwable cause) {
        super(msg, cause);
    }

    @Override
    public RestStatus status() {
        return RestStatus.UNAUTHORIZED;
    }
}