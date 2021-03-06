/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.reactivestreams.Subscription;

import com.linecorp.armeria.common.http.HttpData;
import com.linecorp.armeria.internal.grpc.ArmeriaMessageDeframer;
import com.linecorp.armeria.internal.grpc.ErrorListener;

import io.grpc.Status;

public class HttpStreamReaderTest {

    private static final HttpData DATA = HttpData.ofUtf8("foobarbaz");

    @Rule
    public MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private ErrorListener errorListener;

    @Mock
    private ArmeriaMessageDeframer deframer;

    @Mock
    private Subscription subscription;

    private HttpStreamReader reader;

    @Before
    public void setUp() {
        reader = new HttpStreamReader(deframer, errorListener);
    }

    @Test
    public void subscribe_noServerRequests() {
        reader.onSubscribe(subscription);
        verifyZeroInteractions(subscription);
    }

    @Test
    public void subscribe_hasServerRequests() {
        when(deframer.isStalled()).thenReturn(true);
        reader.onSubscribe(subscription);
        verify(subscription).request(1);
    }

    @Test
    public void onMessage_noServerRequests() {
        reader.onSubscribe(subscription);
        reader.onNext(DATA);
        verify(deframer).deframe(DATA, false);
        verifyZeroInteractions(subscription);
    }

    @Test
    public void onMessage_hasServerRequests() {
        reader.onSubscribe(subscription);
        when(deframer.isStalled()).thenReturn(true);
        reader.onNext(DATA);
        verify(deframer).deframe(DATA, false);
        verify(subscription).request(1);
    }

    @Test
    public void onMessage_deframeError() {
        doThrow(Status.INTERNAL.asRuntimeException())
                .when(deframer).deframe(isA(HttpData.class), anyBoolean());
        reader.onSubscribe(subscription);
        reader.onNext(DATA);
        verify(deframer).deframe(DATA, false);
        verify(errorListener).onError(Status.INTERNAL);
        verify(deframer).close();
    }

    @Test
    public void onMessage_deframeError_errorListenerThrows() {
        doThrow(Status.INTERNAL.asRuntimeException())
                .when(deframer).deframe(isA(HttpData.class), anyBoolean());
        doThrow(new IllegalStateException())
                .when(errorListener).onError(isA(Status.class));
        reader.onSubscribe(subscription);
        assertThatThrownBy(() -> reader.onNext(DATA)).isInstanceOf(IllegalStateException.class);
        verify(deframer).close();
    }

    @Test
    public void clientDone() {
        reader.onComplete();
        verify(deframer).deframe(HttpData.EMPTY_DATA, true);
        verify(deframer).close();
    }

    @Test
    public void serverCancelled() {
        reader.onSubscribe(subscription);
        reader.cancel();
        verify(subscription).cancel();
    }
}
