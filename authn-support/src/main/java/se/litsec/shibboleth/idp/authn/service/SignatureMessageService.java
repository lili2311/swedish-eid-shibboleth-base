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
package se.litsec.shibboleth.idp.authn.service;

import org.opensaml.profile.context.ProfileRequestContext;

import se.litsec.shibboleth.idp.authn.context.SignMessageContext;

/**
 * Service for handling requests from a "Signature Service".
 * 
 * @author Martin Lindström (martin.lindstrom@litsec.se)
 */
public interface SignatureMessageService extends AuthenticationBaseService {

  /**
   * Returns the {@link SignMessageContext} for the current authentication.
   * 
   * @param context
   *          the profile context
   * @return the {@code SignMessageContext}, or {@code null} if none is found
   */
  SignMessageContext getSignMessageContext(ProfileRequestContext<?, ?> context);

  /**
   * Utility method that finds out whether the request that we are processing was sent by a "signature service".
   * 
   * @param context
   *          the profile context
   * @return if the peer is a signature service {@code true} is returned, otherwise {@code false}
   */
  boolean isSignatureServicePeer(ProfileRequestContext<?, ?> context);

}
