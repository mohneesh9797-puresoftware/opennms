/*
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
package org.opennms.core.soa.support;

import java.util.Arrays;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.opennms.core.soa.Registration;
import org.opennms.core.soa.RegistrationHook;
import org.opennms.core.soa.ServiceRegistry;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bridges opennms services with osgi service registry and vice versa. {@link org.opennms.core.soa.ServiceRegistry}
 * 
 * It does this by listening to registrations from each service and publishing new services in the corresponding registry.
 * OSGI services are only published to the core.soa.ServiceRegistry if they are marked with the 'registration.export' attribute.
 * A 'registration.source' attribute indicating the source of the initial registration (either 'osgi' or 'onms').
 * This is added to each synchronized service in order to avoid registration looping.
 * 
 * @author brozow
 *
 */
public class OnmsOSGiBridgeActivator implements RegistrationHook, ServiceListener, BundleActivator {
    private static final Logger LOG = LoggerFactory.getLogger(OnmsOSGiBridgeActivator.class);

    private Lock m_lock = new ReentrantLock();
    private BundleContext m_bundleContext;

    private static final String ONMS_SOURCE = "onms";
    private static final String OSGI_SOURCE = "osgi";
    private static final String REGISTRATION_EXPORT = "registration.export";
    private static final String REGISTRATION_SOURCE = "registration.source";
    private ServiceRegistry m_registry = DefaultServiceRegistry.INSTANCE;
    private Map<Registration, ServiceRegistration<?>> m_onmsRegistration2osgiRegistrationMap = new HashMap<Registration, ServiceRegistration<?>>();
    private Map<ServiceReference<?>, Registration> m_osgiReference2onmsRegistrationMap = new HashMap<ServiceReference<?>, Registration>();

    @Override
    public void start(final BundleContext bundleContext) throws InvalidSyntaxException {
        LOG.debug("Starting BundleActivator {}", this.toString());

        m_lock.lock();

        try {
            m_bundleContext = bundleContext;

            // register for ONMS registrations to forward registrations to OSGi service registry
            getRegistry().addRegistrationHook(this, true);


            // register service listener for export osgi services to forward to ONMS registry
            String exportFilter = "("+REGISTRATION_EXPORT+"=*)";
            m_bundleContext.addServiceListener(this, exportFilter);

            // forward any existing exported OSGi services with ONMS service registry
            ServiceReference<?>[] osgiServices = m_bundleContext.getAllServiceReferences(null, exportFilter);

            if (osgiServices != null) {
                for(ServiceReference<?> reference : osgiServices) {
                    registerWithOnmsRegistry(reference);
                }
            }
        } finally {
            m_lock.unlock();
        }
        LOG.debug("BundleActivator {} started", this.toString());
    }

    @Override
    public void stop(final BundleContext bundleContext) {
        m_lock.lock();
        try {
            m_bundleContext = null;
            // TODO unregister services form both registries with the osgi container stops		
        } finally {
            m_lock.unlock();
        }
    }

    @Override
    public void registrationAdded(final Registration onmsRegistration) {
        final Map<String, String> onmsProperties = onmsRegistration.getProperties() == null ? Collections.<String,String>emptyMap() : onmsRegistration.getProperties();
        if (OSGI_SOURCE.equals(onmsProperties.get(REGISTRATION_SOURCE))) return;

        final Class<?>[] providerInterfaces = onmsRegistration.getProvidedInterfaces();
        final String[] serviceClasses = new String[providerInterfaces.length];


        for(int i = 0; i < providerInterfaces.length; i++) {
            serviceClasses[i] = providerInterfaces[i].getName();
        }

        final Dictionary<String,String> props = new Hashtable<String,String>();
        for(final Entry<String, String> entry : onmsProperties.entrySet()) {
            props.put(entry.getKey(), entry.getValue());
        }
        props.put(REGISTRATION_SOURCE, ONMS_SOURCE);

        m_lock.lock();
        try {
            if (m_bundleContext != null) {
                ServiceRegistration<?> osgiRegistration = m_bundleContext.registerService(serviceClasses, onmsRegistration.getProvider(), props);
                m_onmsRegistration2osgiRegistrationMap.put(onmsRegistration, osgiRegistration);
            }
        } finally {
            m_lock.unlock();
        }
    }

    @Override
    public void registrationRemoved(final Registration onmsRegistration) {
        m_lock.lock();
        
        try {
            final ServiceRegistration<?> osgiRegistration = m_onmsRegistration2osgiRegistrationMap.remove(onmsRegistration);
            if (osgiRegistration == null) {
                return;
            }
            osgiRegistration.unregister();		
        } finally {
            m_lock.unlock();
        }
    }

    @Override
    public void serviceChanged(final ServiceEvent serviceEvent) {
        m_lock.lock();
        try {
            switch(serviceEvent.getType()) {
                case ServiceEvent.REGISTERED:
                    registerWithOnmsRegistry(serviceEvent.getServiceReference());
                    break;
                case ServiceEvent.MODIFIED:
                    registerWithOnmsRegistry(serviceEvent.getServiceReference());
                    break;
                case ServiceEvent.MODIFIED_ENDMATCH:
                    unregisterWithOnmsRegistry(serviceEvent.getServiceReference());
                    break;
                case ServiceEvent.UNREGISTERING:
                    unregisterWithOnmsRegistry(serviceEvent.getServiceReference());
                    break;
            }
        } finally {
            m_lock.unlock();
        }
    }

    private void registerWithOnmsRegistry(ServiceReference<?> reference) {
        LOG.debug("registerWithOnmsRegistry: {}", reference.getBundle());

        // skip this service if this should not be exported
        if (!isOnmsExported(reference)) return;

        // skip this service if its came from the opennms registry originally
        if (isOnmsSource(reference)) return;

        // if this service is already registered then skip it
        if (m_osgiReference2onmsRegistrationMap.containsKey(reference)) return;

        String[] classNames = (String[]) reference.getProperty(Constants.OBJECTCLASS);

        try {
            Class<?>[] providerInterfaces = findClasses(classNames);

            Object provider = m_bundleContext.getService(reference);

            Map<String, String> properties = new LinkedHashMap<String, String>();

            for(String key : reference.getPropertyKeys()) {
                Object val = reference.getProperty(key);
                StringBuilder buf = new StringBuilder();
                if (val instanceof Object[]) {
                    Object[] a = (Object[])val;
                    for(int i = 0; i < a.length; i++) {
                        if (i != 0) buf.append(',');
                        buf.append(a[i]);
                    }
                } else {
                    buf.append(val);
                }
                properties.put(key, buf.toString());
            }

            properties.put(REGISTRATION_SOURCE, OSGI_SOURCE);

            LOG.debug("OnmsOSGiBridgeActivator: registering...");
            final Registration onmsRegistration = getRegistry().register(provider, properties, providerInterfaces);
            LOG.debug("OnmsOSGiBridgeActivator: registry = {}", getRegistry());
            m_osgiReference2onmsRegistrationMap.put(reference, onmsRegistration);
            LOG.info("OnmsOSGiBridgeActivator: registered provider {} for interfaces: {} with properties: {}", provider, Arrays.toString(providerInterfaces), properties);
        } catch (final ClassNotFoundException e) {
            LOG.warn("OnmsOSGiBridgeActivator: Unable to find class used by exported OSGi service", e);
        }
    }

    private boolean isOnmsExported(ServiceReference<?> reference) {
        return Arrays.asList(reference.getPropertyKeys()).contains(REGISTRATION_EXPORT);
    }

    private boolean isOnmsSource(ServiceReference<?> reference) {
        return ONMS_SOURCE.equals(reference.getProperty(REGISTRATION_SOURCE));
    }

    private Class<?>[] findClasses(String[] classNames) throws ClassNotFoundException {
        Class<?>[] providerInterfaces = new Class<?>[classNames.length];

        for(int i = 0; i < classNames.length; i++) {
            providerInterfaces[i] = Class.forName(classNames[i]);
        }

        return providerInterfaces;

    }

    private void unregisterWithOnmsRegistry(ServiceReference<?> reference) {

        Registration onmsRegistration = m_osgiReference2onmsRegistrationMap.remove(reference);

        if (onmsRegistration == null) return;

        onmsRegistration.unregister();

    }

    private ServiceRegistry getRegistry() {
        return m_registry;
    }

}
