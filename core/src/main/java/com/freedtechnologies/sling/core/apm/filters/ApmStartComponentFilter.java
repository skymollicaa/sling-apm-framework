package com.freedtechnologies.sling.core.apm.filters;

import com.freedtechnologies.sling.core.apm.services.ApmAgent;
import com.freedtechnologies.sling.core.apm.services.ApmConfig;
import org.apache.felix.scr.annotations.*;
import org.apache.felix.scr.annotations.sling.SlingFilter;
import org.apache.felix.scr.annotations.sling.SlingFilterScope;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.servlets.ServletResolver;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Simple servlet filter component that logs incoming requests.
 */

/**
 * Filter that logs to APM agents when a component starts rendering as part of an overall transaction
 */
@References({@Reference(name = "apmAgent", cardinality = ReferenceCardinality.OPTIONAL_MULTIPLE, policy = ReferencePolicy.DYNAMIC, strategy = ReferenceStrategy.EVENT, referenceInterface=ApmAgent.class, bind="bindApmAgent", unbind="unbindApmAgent")})
@SlingFilter(scope= SlingFilterScope.INCLUDE, order = 0,
        description="Sends APM segment information to provider via filter", name="Apm Component Start Filter")
@Service(value = Filter.class)
public class ApmStartComponentFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ApmStartComponentFilter.class);

    private static final Pattern SERVLET_PATTERN = Pattern.compile("^.+Using servlet (.+)$");

    @Reference
    private ApmConfig apmConfig;

    private final List<ApmAgent> apmAgents = Collections.synchronizedList(new ArrayList<>());

    protected synchronized void bindApmAgent(ServiceReference ref) {
        log.trace("in bind");
        ApmAgent config = (ApmAgent) ref.getBundle().getBundleContext().getService(ref);
        log.trace("binding apm agent: " + config.getClass().getSimpleName());
        if(config.isEnabled() && config.logComponents()) {
            apmAgents.add(config);
        }
    }

    protected synchronized void unbindApmAgent(ServiceReference ref) {
        log.trace("in unbind");
        ApmAgent config = (ApmAgent) ref.getBundle().getBundleContext().getService(ref);
        log.trace("unbind apm agent: " + config.getClass().getSimpleName());
        apmAgents.remove(config);
    }

    @Reference
    ServletResolver servletResolver;

    @Override
    public void init(FilterConfig paramFilterConfig) throws ServletException {

    }

    /**
     * starts apm recording of individual component being rendered
     * @param servletRequest
     * @param servletResponse
     * @param filterChain
     * @throws IOException
     * @throws ServletException
     */
    @Override
    public void doFilter(ServletRequest servletRequest,
                         ServletResponse servletResponse, FilterChain filterChain)
            throws IOException, ServletException {

        SlingHttpServletRequest request = (SlingHttpServletRequest) servletRequest;
        try {
            log.trace("in filter start");
            // we don't need to execute this logic if no apm agents are configured
            if(apmAgents.size()==0) {
                log.trace("no agents found, exiting");
                return;
            }

            Servlet servlet = servletResolver.resolveServlet(request);
            synchronized (apmAgents) {
                for(ApmAgent agent : apmAgents) {
                    // only log if agent is enabled
                    if(agent.isEnabled()) {
                        Object result = agent.startComponentMetric(servlet.getServletConfig().getServletName());
                        request.setAttribute("apm.start.component." + servlet.getServletConfig().getServletName()  + agent.getClass().getSimpleName(), result);
                        log.trace("sent start span metric");
                    }
                }
            }

        } catch (Exception e) {
            log.warn("could not create apm span", e);
        } finally {
            filterChain.doFilter(servletRequest, servletResponse);
        }
    }


    @Activate
    @Modified
    protected void activate(final BundleContext bundleContext,
                            final Map<String, Object> configuration) {
    }

    @Override
    public void destroy() {
    }

}