/*
 * Copyright 2018-present yangguo@outlook.com
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
package info.yangguo.yfs;

import info.yangguo.yfs.config.ClusterConfig;
import info.yangguo.yfs.config.Watchdog;
import info.yangguo.yfs.util.NetUtils;
import info.yangguo.yfs.util.GatewayHttpHeaderNames;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.*;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ThreadPoolConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.List;

public class Application {
    private static Logger logger = LoggerFactory.getLogger(Application.class);

    public static void main(String[] args) {
        ThreadPoolConfiguration threadPoolConfiguration = new ThreadPoolConfiguration();
        threadPoolConfiguration.withAcceptorThreads(Constant.AcceptorThreads);
        threadPoolConfiguration.withClientToProxyWorkerThreads(Constant.ClientToProxyWorkerThreads);
        threadPoolConfiguration.withProxyToServerWorkerThreads(Constant.ProxyToServerWorkerThreads);

        ClusterConfig clusterConfig = new ClusterConfig();

        Watchdog watchdog = new Watchdog(clusterConfig);
        Thread watchdogThread = new Thread(watchdog);
        watchdogThread.setDaemon(true);
        watchdogThread.start();

        InetSocketAddress inetSocketAddress = new InetSocketAddress(Constant.ServerPort);
        HttpProxyServerBootstrap httpProxyServerBootstrap = DefaultHttpProxyServer.bootstrap()
                .withAddress(inetSocketAddress);
        httpProxyServerBootstrap.withIdleConnectionTimeout(Integer.valueOf(Constant.IdleConnectionTimeout));
        httpProxyServerBootstrap.withServerResolver(HostResolverImpl.getSingleton(clusterConfig));
        httpProxyServerBootstrap.withAllowRequestToOriginServer(true)
                .withProxyAlias("yfs-gateway")
                .withThreadPoolConfiguration(threadPoolConfiguration)
                //XFF设置
                .plusActivityTracker(new ActivityTrackerAdapter() {
                    @Override
                    public void requestReceivedFromClient(FlowContext flowContext,
                                                          HttpRequest httpRequest) {

                        StringBuilder xff = new StringBuilder();
                        List<String> headerValues1 = httpRequest.headers().getAll(GatewayHttpHeaderNames.X_FORWARDED_FOR);
                        if (headerValues1.size() > 0 && headerValues1.get(0) != null) {
                            //逗号面一定要带一个空格
                            xff.append(headerValues1.get(0)).append(", ");
                        }
                        xff.append(NetUtils.getLocalHost());
                        httpRequest.headers().set(GatewayHttpHeaderNames.X_FORWARDED_FOR, xff.toString());
                    }
                })
                //X-Real-IP设置
                .plusActivityTracker(
                        new ActivityTrackerAdapter() {
                            @Override
                            public void requestReceivedFromClient(FlowContext flowContext,
                                                                  HttpRequest httpRequest) {
                                String realIp = httpRequest.headers().get(GatewayHttpHeaderNames.X_REAL_IP);
                                if (realIp == null) {
                                    String remoteAddress = flowContext.getClientAddress().getAddress().getHostAddress();
                                    httpRequest.headers().add(GatewayHttpHeaderNames.X_REAL_IP, remoteAddress);
                                }
                            }
                        }
                )
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
                        return new HttpFilterAdapterImpl(originalRequest, ctx, watchdog, clusterConfig);
                    }
                })
                .start();
        logger.info("yfs-gateway has been started");
    }
}
