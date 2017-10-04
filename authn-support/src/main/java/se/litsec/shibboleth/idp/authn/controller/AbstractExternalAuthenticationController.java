/*
 * The swedish-eid-shibboleth-base is an open-source package that provides
 * an easy-to-use re-packaging of the Shibboleth Identity Provider for use
 * with the Swedish eID Framework. 
 *
 * More details on <https://github.com/litsec/swedish-eid-shibboleth-base> 
 * Copyright (C) 2017 Litsec AB
 * 
 * This program is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License as published by 
 * the Free Software Foundation, either version 3 of the License, or 
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the 
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License 
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package se.litsec.shibboleth.idp.authn.controller;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.security.auth.Subject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.joda.time.DateTime;
import org.opensaml.messaging.context.MessageContext;
import org.opensaml.messaging.context.navigate.ContextDataLookupFunction;
import org.opensaml.messaging.context.navigate.MessageLookup;
import org.opensaml.profile.context.ProfileRequestContext;
import org.opensaml.profile.context.navigate.InboundMessageContextLookup;
import org.opensaml.saml.common.messaging.context.SAMLBindingContext;
import org.opensaml.saml.common.messaging.context.SAMLMetadataContext;
import org.opensaml.saml.common.messaging.context.SAMLPeerEntityContext;
import org.opensaml.saml.saml2.core.Attribute;
import org.opensaml.saml.saml2.core.AuthnRequest;
import org.opensaml.saml.saml2.core.Status;
import org.opensaml.saml.saml2.core.StatusCode;
import org.opensaml.saml.saml2.metadata.EntityDescriptor;
import org.opensaml.xmlsec.encryption.support.DecryptionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import com.google.common.base.Function;
import com.google.common.base.Functions;

import net.shibboleth.idp.attribute.IdPAttribute;
import net.shibboleth.idp.attribute.StringAttributeValue;
import net.shibboleth.idp.authn.AuthenticationFlowDescriptor;
import net.shibboleth.idp.authn.ExternalAuthentication;
import net.shibboleth.idp.authn.ExternalAuthenticationException;
import net.shibboleth.idp.authn.context.AuthenticationContext;
import net.shibboleth.idp.authn.context.RequestedPrincipalContext;
import net.shibboleth.idp.authn.principal.IdPAttributePrincipal;
import net.shibboleth.idp.authn.principal.UsernamePrincipal;
import net.shibboleth.idp.saml.authn.principal.AuthnContextClassRefPrincipal;
import se.litsec.opensaml.saml2.attribute.AttributeUtils;
import se.litsec.shibboleth.idp.attribute.resolver.SAML2AttributeNameToIdMapperService;
import se.litsec.shibboleth.idp.authn.ExtAuthnEventIds;
import se.litsec.shibboleth.idp.authn.IdpErrorStatusException;
import se.litsec.shibboleth.idp.authn.context.strategy.RequestedPrincipalContextLookup;
import se.litsec.shibboleth.idp.authn.context.strategy.SAMLPeerEntityContextLookup;
import se.litsec.shibboleth.idp.context.ProxiedStatusContext;
import se.litsec.shibboleth.idp.subsystem.signservice.SignMessageDecryptionService;
import se.litsec.swedisheid.opensaml.saml2.metadata.entitycategory.EntityCategoryConstants;
import se.litsec.swedisheid.opensaml.saml2.metadata.entitycategory.EntityCategoryMetadataHelper;
import se.litsec.swedisheid.opensaml.saml2.signservice.dss.Message;
import se.litsec.swedisheid.opensaml.saml2.signservice.dss.SignMessage;

/**
 * Abstract base class for controllers implementing "external authentication".
 * 
 * @author Martin Lindström (martin.lindstrom@litsec.se)
 */
public abstract class AbstractExternalAuthenticationController implements InitializingBean {

  /** The name for the session attribute where we store the external authentication key. */
  public static final String EXTAUTHN_KEY_ATTRIBUTE_NAME = "se.litsec.shibboleth.idp.authn.ExternalAuthnKey";

  /** Logging instance. */
  private final Logger logger = LoggerFactory.getLogger(AbstractExternalAuthenticationController.class);

  /** Helper that maps from SAML 2 attribute names to their corresponding Shibboleth attribute id:s. */
  private SAML2AttributeNameToIdMapperService attributeToIdMapping;

  /** The SignMessageDecrypter service. */
  private SignMessageDecryptionService signMessageDecrypter;

  /** The name of the Shibboleth flow that this controller supports. */
  private String flowName;

  /** Strategy used to locate the {@link AuthnRequest} to operate on. */
  @SuppressWarnings("rawtypes") protected Function<ProfileRequestContext, AuthnRequest> requestLookupStrategy = Functions.compose(
    new MessageLookup<>(AuthnRequest.class), new InboundMessageContextLookup());

  /** Strategy used to locate the SP {@link EntityDescriptor} (metadata). */
  @SuppressWarnings("rawtypes") protected Function<ProfileRequestContext, EntityDescriptor> peerMetadataLookupStrategy = Functions.compose(
    new PeerMetadataContextLookup(), Functions.compose(new SAMLPeerEntityContextLookup(), new InboundMessageContextLookup()));

  @SuppressWarnings("rawtypes") protected Function<ProfileRequestContext, SAMLBindingContext> samlBindingContextLookupStrategy = Functions
    .compose(new SAMLBindingContextLookup(), new InboundMessageContextLookup());

  /** Strategy that gives us the AuthenticationContext. */
  @SuppressWarnings("rawtypes") protected Function<ProfileRequestContext, AuthenticationContext> authenticationContextLookupStrategy = new AuthenticationContextLookup();

  /** Strategy used to locate the requested LoA URI:s. */
  @SuppressWarnings("rawtypes") protected Function<ProfileRequestContext, RequestedPrincipalContext> requestedPrincipalLookupStrategy = Functions
    .compose(new RequestedPrincipalContextLookup(), this.authenticationContextLookupStrategy);

  /**
   * Main entry point for the external authentication controller. The implementation starts a Shibboleth external
   * authentication process and hands over the control to
   * {@link #doExternalAuthentication(HttpServletRequest, HttpServletResponse, String, ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @return a model and view object
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   */
  @RequestMapping(method = RequestMethod.GET)
  public final ModelAndView processExternalAuthentication(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
      throws ExternalAuthenticationException, IOException {

    // Start the external authentication process ...
    //
    final String key = ExternalAuthentication.startExternalAuthentication(httpRequest);
    logger.debug("External authentication started. [key='{}',client-ip-address='{}']", key, httpRequest.getRemoteAddr());

    final ProfileRequestContext<?, ?> profileRequestContext = ExternalAuthentication.getProfileRequestContext(key, httpRequest);

    // Store the authentication key in the HTTP session.
    //
    HttpSession session = httpRequest.getSession();
    session.setAttribute(EXTAUTHN_KEY_ATTRIBUTE_NAME, key);

    // Perform some initial checks to ensure the request is valid.
    //

    // Hand over to implementation ...
    //
    return this.doExternalAuthentication(httpRequest, httpResponse, key, profileRequestContext);
  }

  //
  // TODO: This method should set a context holding the requests's LoA:s
  //
  protected void initializeExternalAuthentication(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String key,
      ProfileRequestContext<?, ?> profileRequestContext) throws ExternalAuthenticationException, IOException, IdpErrorStatusException {
    
    final AuthnRequest authnRequest = this.getAuthnRequest(profileRequestContext);
    
    // First handle the SignMessage if it's there.
    //
    final SignMessage signMessage = this.getSignMessage(profileRequestContext);
    if (signMessage != null) {
      
      // TODO: Check display entity
      
    }
       
    final boolean isSignService = this.isSignatureServicePeer(profileRequestContext);
    
    
    final String spEntityID = authnRequest.getIssuer().getValue();

    if (signMessage != null && !isSignService) {
      logger.info(
        "SignMessage extension was included in AuthnRequest by relying party '{}' that is not a sign service. Will ignore SignMessage extension.", spEntityID);
      
      // TODO: Or should we throw?
    }

    if (isSignService) {
      if (signMessage == null) {
        logger.info("AuthnRequest from Signature Service '{}' does not contain a SignMessage extension ...", spEntityID);
        
        // Check all requested LoA:s and filter away those the includes sigmessage. 
        // Are there any possible LoA:s left? If not, return error.        
      }
      else {
        
      }
    }

  }
  
  //protected abstract boolean supportsSignMessage(SignMessage signMessage);

  /**
   * Abstract method that must be implemented by subclasses in order to implement the authentication.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param key
   *          the Shibboleth external authentication key
   * @param profileRequestContext
   *          the Shibboleth request context
   * @return a model and view object
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   */
  protected abstract ModelAndView doExternalAuthentication(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String key,
      ProfileRequestContext<?, ?> profileRequestContext) throws ExternalAuthenticationException, IOException;

  /**
   * Returns the name that this authenticator has. Mainly used for logging.
   * 
   * @return the authenticator name
   */
  public abstract String getAuthenticatorName();

  /**
   * Returns the Shibboleth external authentication key for the current session.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the Shibboleth external authentication key
   * @throws ExternalAuthenticationException
   *           if no active session exists
   */
  protected String getExternalAuthenticationKey(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    String key = (String) httpRequest.getSession().getAttribute(EXTAUTHN_KEY_ATTRIBUTE_NAME);
    if (key == null) {
      throw new ExternalAuthenticationException("No external authentication process is active");
    }
    return key;
  }

  /**
   * Returns the {@link ProfileRequestContext} object associated with the current authentication process.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the context
   * @throws ExternalAuthenticationException
   *           if no active session exists
   */
  protected ProfileRequestContext<?, ?> getProfileRequestContext(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return ExternalAuthentication.getProfileRequestContext(this.getExternalAuthenticationKey(httpRequest), httpRequest);
  }

  /**
   * Method that should be invoked to exit the external authentication process with a successful result.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param subject
   *          the subject of the authenticated user (contains the attributes of the user)
   * @param authnInstant
   *          the authentication instant - if {@code null} the current time will be used
   * @param cacheForSSO
   *          should the result be cached for later SSO? If {@code null}, the result will be cached
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   */
  protected void success(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Subject subject,
      DateTime authnInstant, Boolean cacheForSSO) throws ExternalAuthenticationException, IOException {

    final String key = this.getExternalAuthenticationKey(httpRequest);

    // Assign the authenticated subject.
    httpRequest.setAttribute(ExternalAuthentication.SUBJECT_KEY, subject);

    // Assign the authentication instant.
    if (authnInstant == null) {
      authnInstant = new DateTime();
    }
    httpRequest.setAttribute(ExternalAuthentication.AUTHENTICATION_INSTANT_KEY, authnInstant);

    // Tell Shibboleth processing whether this result should be cached for SSO or not.
    httpRequest.setAttribute(ExternalAuthentication.DONOTCACHE_KEY, cacheForSSO == null ? Boolean.FALSE : !cacheForSSO);

    // Finish the external authentication task and return to the flow.
    ExternalAuthentication.finishExternalAuthentication(key, httpRequest, httpResponse);
    httpRequest.getSession().removeAttribute(EXTAUTHN_KEY_ATTRIBUTE_NAME);
  }

  /**
   * Method that should be invoked to exit the external authentication process with a successful result.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param principal
   *          the principal that was authenticated
   * @param attributes
   *          the attributes to release
   * @param authnContextClassUri
   *          the authentication context class URI (LoA)
   * @param authnInstant
   *          the authentication instant - if {@code null} the current time will be used
   * @param cacheForSSO
   *          should the result be cached for later SSO? If {@code null}, the result will be cached
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   */
  protected void success(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String principal, List<Attribute> attributes,
      String authnContextClassUri, DateTime authnInstant, Boolean cacheForSSO) throws ExternalAuthenticationException, IOException {

    SubjectBuilder builder = this.getSubjectBuilder(principal);
    for (Attribute a : attributes) {
      builder.attribute(a);
    }
    builder.authnContextClassRef(authnContextClassUri);
    this.success(httpRequest, httpResponse, builder.build(), authnInstant, cacheForSSO);
  }

  /**
   * Method that should be invoked before exiting the external authentication process and indicate that the user
   * cancelled the authentication.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   */
  protected void cancel(HttpServletRequest httpRequest, HttpServletResponse httpResponse)
      throws ExternalAuthenticationException, IOException {
    this.error(httpRequest, httpResponse, ExtAuthnEventIds.CANCEL_AUTHN);
  }

  /**
   * Method that should be invoked to exit the external authentication process with an error.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param authnEventId
   *          the Shibboleth event ID to signal
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   * @see #error(HttpServletRequest, HttpServletResponse, Exception)
   * @see #error(HttpServletRequest, HttpServletResponse, Status)
   */
  protected void error(HttpServletRequest httpRequest, HttpServletResponse httpResponse, String authnEventId)
      throws ExternalAuthenticationException, IOException {

    final String key = this.getExternalAuthenticationKey(httpRequest);
    httpRequest.setAttribute(ExternalAuthentication.AUTHENTICATION_ERROR_KEY, authnEventId);
    ExternalAuthentication.finishExternalAuthentication(key, httpRequest, httpResponse);
    httpRequest.getSession().removeAttribute(EXTAUTHN_KEY_ATTRIBUTE_NAME);
  }

  /**
   * Method that should be invoked to exit the external authentication process with an error.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param error
   *          the error exception
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   * @see #error(HttpServletRequest, HttpServletResponse, String)
   * @see #error(HttpServletRequest, HttpServletResponse, Status)
   */
  protected void error(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Exception error)
      throws ExternalAuthenticationException, IOException {

    final String key = this.getExternalAuthenticationKey(httpRequest);

    if (error instanceof IdpErrorStatusException) {
      Status s = ((IdpErrorStatusException) error).getStatus();
      if (s.getStatusCode() == null || StatusCode.SUCCESS.equals(s.getStatusCode().getValue())) {
        throw new IllegalArgumentException("Bad call to error - Status is successful");
      }
      ProfileRequestContext<?, ?> profileRequestContext = this.getProfileRequestContext(httpRequest);
      profileRequestContext.addSubcontext(new ProxiedStatusContext(s), true);
    }

    httpRequest.setAttribute(ExternalAuthentication.AUTHENTICATION_EXCEPTION_KEY, error);
    ExternalAuthentication.finishExternalAuthentication(key, httpRequest, httpResponse);
    httpRequest.getSession().removeAttribute(EXTAUTHN_KEY_ATTRIBUTE_NAME);
  }

  /**
   * Method that should be invoked to exit the external authentication process with an error where the SAML status to
   * respond with is given.
   * 
   * @param httpRequest
   *          the HTTP request
   * @param httpResponse
   *          the HTTP response
   * @param errorStatus
   *          the SAML status
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   * @throws IOException
   *           for IO errors
   * @see #error(HttpServletRequest, HttpServletResponse, String)
   * @see #error(HttpServletRequest, HttpServletResponse, Exception)
   */
  protected void error(HttpServletRequest httpRequest, HttpServletResponse httpResponse, Status errorStatus)
      throws ExternalAuthenticationException, IOException {
    this.error(httpRequest, httpResponse, new IdpErrorStatusException(errorStatus));
  }

  /**
   * Utility method that may be used to obtain the {@link AuthnRequest} message that initiated this authentication
   * process.
   * 
   * @param context
   *          the profile context
   * @return the authentication request message
   * @see #getAuthnRequest(HttpServletRequest)
   */
  protected AuthnRequest getAuthnRequest(ProfileRequestContext<?, ?> context) {
    return this.requestLookupStrategy.apply(context);
  }

  /**
   * See {@link #getAuthnRequest(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the authentication request message
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected AuthnRequest getAuthnRequest(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getAuthnRequest(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Utility method that may be used to obtain the SAML metadata for the peer (i.e., the Service Provider) that sent the
   * authentication request.
   * 
   * @param context
   *          the profile context
   * @return the entity descriptor
   * @see #getPeerMetadata(HttpServletRequest)
   */
  protected EntityDescriptor getPeerMetadata(ProfileRequestContext<?, ?> context) {
    return this.peerMetadataLookupStrategy.apply(context);
  }

  /**
   * See {@link #getPeerMetadata(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the entity descriptor
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected EntityDescriptor getPeerMetadata(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getPeerMetadata(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Utility method that may be used to obtain the binding that was used to pass the AuthnRequest message.
   * 
   * @param context
   *          the profile context
   * @return the binding URI
   * @see #getBinding(HttpServletRequest)
   */
  protected String getBinding(ProfileRequestContext<?, ?> context) {
    SAMLBindingContext samlBinding = this.samlBindingContextLookupStrategy.apply(context);
    return samlBinding != null ? samlBinding.getBindingUri() : null;
  }

  /**
   * See {@link #getBinding(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the binding URI
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected String getBinding(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getBinding(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Utility method that may be used to obtain the Relay State for the request.
   * 
   * @param context
   *          the profile context
   * @return the relay state
   * @see #getRelayState(HttpServletRequest)
   */
  protected String getRelayState(ProfileRequestContext<?, ?> context) {
    SAMLBindingContext samlBinding = this.samlBindingContextLookupStrategy.apply(context);
    return samlBinding != null ? samlBinding.getRelayState() : null;
  }

  /**
   * See {@link #getRelayState(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return the relay state
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected String getRelayState(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getRelayState(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Returns a list of the requested AuthnContextClassRef URI:s (level of assurance URI:s) that match what is supported
   * by this authenticator.
   * 
   * @param context
   *          the profile context
   * @return requested AuthnContextClassRef URI:s
   */
  protected List<String> getRequestedAuthnContextClassRefs(ProfileRequestContext<?, ?> context) {
    RequestedPrincipalContext requestedPrincipalContext = this.requestedPrincipalLookupStrategy.apply(context);
    if (requestedPrincipalContext == null) {
      return Collections.emptyList();
    }

    List<String> supportedUri = this.getSupportedAuthnContextClassRefs(context);

    return requestedPrincipalContext.getRequestedPrincipals()
      .stream()
      .map(p -> p.getName())
      .filter(u -> supportedUri.contains(u))
      .collect(Collectors.toList());
  }

  /**
   * See {@link #getRequestedAuthnContextClassRefs(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          The HTTP request
   * @return requested AuthnContextClassRef URI:s
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected List<String> getRequestedAuthnContextClassRefs(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getRequestedAuthnContextClassRefs(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Returns a list of AuthnContextClassRef URI:s (level of assurance URI:s) that is supported by this authenticator.
   * 
   * @param context
   *          the profile context
   * @return a list of supported AuthnContextClassRef URI:s
   */
  protected List<String> getSupportedAuthnContextClassRefs(ProfileRequestContext<?, ?> context) {
    AuthenticationContext authenticationContext = this.authenticationContextLookupStrategy.apply(context);
    if (authenticationContext == null) {
      return Collections.emptyList();
    }
    AuthenticationFlowDescriptor authenticationFlowDescriptor = authenticationContext.getAvailableFlows().get(this.flowName);
    if (authenticationFlowDescriptor == null) {
      logger.error("No authentication flow descriptor exists for {}", this.flowName);
      return Collections.emptyList();
    }
    return authenticationFlowDescriptor.getSupportedPrincipals()
      .stream()
      .filter(AuthnContextClassRefPrincipal.class::isInstance)
      .map(p -> p.getName())
      .collect(Collectors.toList());
  }

  /**
   * Utility method that finds out whether the request that we are processing was sent by a "signature service".
   * 
   * @param context
   *          the profile context
   * @return if the peer is a signature service {@code true} is returned, otherwise {@code false}
   * @see #isSignatureServicePeer(HttpServletRequest)
   */
  protected boolean isSignatureServicePeer(ProfileRequestContext<?, ?> context) {
    EntityDescriptor peerMetadata = this.getPeerMetadata(context);
    if (peerMetadata == null) {
      logger.error("No metadata available for connecting SP");
      return false;
    }
    return EntityCategoryMetadataHelper.getEntityCategories(peerMetadata)
      .stream()
      .filter(c -> EntityCategoryConstants.SERVICE_TYPE_CATEGORY_SIGSERVICE.getUri().equals(c))
      .findFirst()
      .isPresent();
  }

  /**
   * See {@link #isSignatureServicePeer(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          The HTTP request
   * @return if the peer is a signature service {@code true} is returned, otherwise {@code false}
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected boolean isSignatureServicePeer(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.isSignatureServicePeer(this.getProfileRequestContext(httpRequest));
  }

  /**
   * If the IdP was called by a signature service ({@link #isSignatureServicePeer(ProfileRequestContext)} returns
   * {@code true}), the {@code AuthnRequest} should contain a {@code SignMessage} element extension. This method returns
   * this object.
   * 
   * @param context
   *          the profile context
   * @return a {@code SignMessage} or {@code null} if none is available
   */
  protected SignMessage getSignMessage(ProfileRequestContext<?, ?> context) {
    AuthnRequest authnRequest = this.getAuthnRequest(context);
    if (authnRequest == null) {
      logger.error("No AuthnRequest is available");
      return null;
    }
    if (authnRequest.getExtensions() == null) {
      return null;
    }
    return authnRequest.getExtensions()
      .getUnknownXMLObjects()
      .stream()
      .filter(SignMessage.class::isInstance)
      .map(SignMessage.class::cast)
      .findFirst()
      .orElse(null);
  }

  /**
   * See {@link #getSignMessage(ProfileRequestContext)}.
   * 
   * @param httpRequest
   *          the HTTP request
   * @return a {@code SignMessage} or {@code null} if none is available
   * @throws ExternalAuthenticationException
   *           for Shibboleth session errors
   */
  protected SignMessage getSignMessage(HttpServletRequest httpRequest) throws ExternalAuthenticationException {
    return this.getSignMessage(this.getProfileRequestContext(httpRequest));
  }

  /**
   * Decrypts an encrypted {@link SignMessage}.
   * 
   * @param signMessage
   *          the message holding the encrypted message
   * @return a cleartext {@link Message} object
   * @throws DecryptionException
   *           for decryption errors
   */
  protected Message decryptSignMessage(SignMessage signMessage) throws DecryptionException {
    return this.signMessageDecrypter.decrypt(signMessage);
  }

  /**
   * Lookup function for finding a {@link AuthenticationContext}.
   */
  @SuppressWarnings("rawtypes")
  public static class AuthenticationContextLookup implements ContextDataLookupFunction<ProfileRequestContext, AuthenticationContext> {

    @Override
    public AuthenticationContext apply(ProfileRequestContext input) {
      return input != null ? input.getSubcontext(AuthenticationContext.class, false) : null;
    }
  }

  /**
   * Lookup function for finding a {@link EntityDescriptor} in a {@code SAMLPeerEntityContext}.
   */
  public static class PeerMetadataContextLookup implements ContextDataLookupFunction<SAMLPeerEntityContext, EntityDescriptor> {

    @Override
    public EntityDescriptor apply(SAMLPeerEntityContext input) {
      if (input != null) {
        SAMLMetadataContext metadataContext = input.getSubcontext(SAMLMetadataContext.class, false);
        if (metadataContext != null) {
          return metadataContext.getEntityDescriptor();
        }
      }
      return null;
    }
  }

  /**
   * Lookup function for finding a {@link SAMLBindingContext}.
   */
  @SuppressWarnings("rawtypes")
  public static class SAMLBindingContextLookup implements ContextDataLookupFunction<MessageContext, SAMLBindingContext> {

    @Override
    public SAMLBindingContext apply(MessageContext input) {
      if (input != null) {
        return input.getSubcontext(SAMLBindingContext.class, false);
      }
      return null;
    }
  }

  /**
   * Adds the service that provides mappings from SAML 2 attribute names to their corresponding Shibboleth attribute
   * id:s.
   * 
   * @param attributeToIdMapping
   *          mapper service
   */
  public void setAttributeToIdMapping(SAML2AttributeNameToIdMapperService attributeToIdMapping) {
    this.attributeToIdMapping = attributeToIdMapping;
  }

  /**
   * Assigns the sign message decrypter.
   * 
   * @param signMessageDecrypter
   *          the decrypter
   */
  public void setSignMessageDecrypter(SignMessageDecryptionService signMessageDecrypter) {
    this.signMessageDecrypter = signMessageDecrypter;
  }

  /**
   * Assigns the flow name for the authentication flow that this controller supports, e.g. "authn/External".
   * 
   * @param flowName
   *          the flow name
   */
  public void setFlowName(String flowName) {
    this.flowName = flowName;
  }

  /** {@inheritDoc} */
  @Override
  public void afterPropertiesSet() throws Exception {
    Assert.notNull(this.attributeToIdMapping, "Property 'attributeToIdMapping' must be assigned");
    Assert.notNull(this.signMessageDecrypter, "The property 'signMessageDecrypter' must be assigned");
    Assert.notNull(this.flowName, "Property 'flowName' must be assigned");
  }

  protected SubjectBuilder getSubjectBuilder(String principal) {
    return new SubjectBuilder(principal, this.attributeToIdMapping);
  }

  /**
   * Helper for building {@link Subject} objects.
   */
  protected static class SubjectBuilder {

    /** Logging instance. */
    private final Logger logger = LoggerFactory.getLogger(SubjectBuilder.class);

    /** The subject being built. */
    private Subject subject;

    /** Maps between attribute names and corresponding Shibboleth ID:s. */
    private SAML2AttributeNameToIdMapperService attributeToIdMapping;

    /**
     * Constructor.
     * 
     * @param principal
     *          the principal ID
     * @param attributeToIdMapping
     *          the attribute mapper
     */
    private SubjectBuilder(String principal, SAML2AttributeNameToIdMapperService attributeToIdMapping) {
      this.attributeToIdMapping = attributeToIdMapping;
      this.subject = new Subject();
      subject.getPrincipals().add(new UsernamePrincipal(principal));
    }

    /**
     * Builds the subject object.
     * 
     * @return the {@code Subject} object
     */
    public Subject build() {
      return this.subject;
    }

    /**
     * Adds an attribute by giving the Shibboleth attribute ID and one or more values.
     * 
     * @param attributeId
     *          the Shibboleth attribute ID
     * @param values
     *          the value(s)
     * @return the builder
     */
    public SubjectBuilder shibbolethAttribute(String attributeId, String... values) {
      if (values == null) {
        return this;
      }
      IdPAttribute attr = new IdPAttribute(attributeId);
      attr.setValues(Arrays.asList(values).stream().map(v -> new StringAttributeValue(v)).collect(Collectors.toList()));
      this.subject.getPrincipals().add(new IdPAttributePrincipal(attr));
      return this;
    }

    /**
     * Adds an attribute by first transforming it to Shibboleth's representation.
     * 
     * @param name
     *          the attribute name
     * @param values
     *          the attribute value(s)
     * @return the builder
     * @throws IllegalArgumentException
     *           if no mapping exists between the supplied attribute name and a Shibboleth attribute ID
     */
    public SubjectBuilder attribute(String name, String... values) throws IllegalArgumentException {
      String attributeId = this.attributeToIdMapping.getAttributeID(name);
      if (attributeId == null) {
        logger.error("No mapping exists for attribute '{}'", name);
        return this;
      }
      return this.shibbolethAttribute(attributeId, values);
    }

    /**
     * Adds an attribute by first transforming it to Shibboleth's representation.
     * 
     * @param attribute
     *          the attribute to add
     * @return the builder
     * @throws IllegalArgumentException
     *           if no mapping exists between the supplied attribute name and a Shibboleth attribute ID
     */
    public SubjectBuilder attribute(Attribute attribute) throws IllegalArgumentException {
      return this.attribute(attribute.getName(), AttributeUtils.getAttributeStringValues(attribute).toArray(new String[] {}));
    }

    /**
     * Adds the {@code AuthenticationContextClassRef} as a {@link AuthnContextClassRefPrincipal} object.
     * 
     * @param uri
     *          the AuthnContext class reference URI
     * @return the builder
     */
    public SubjectBuilder authnContextClassRef(String uri) {
      this.subject.getPrincipals().add(new AuthnContextClassRefPrincipal(uri));
      return this;
    }
  }

}
