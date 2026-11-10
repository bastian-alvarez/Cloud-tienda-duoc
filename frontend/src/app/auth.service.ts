import { Injectable } from '@angular/core';
import { BehaviorSubject } from 'rxjs';
import { User, UserManager } from 'oidc-client-ts';
import { environment } from './environment';

const appBaseUrl = new URL(document.baseURI);
const appBasePath = appBaseUrl.pathname;
const appRootUrl = `${appBaseUrl.origin}${appBasePath === '/' ? '' : appBasePath.replace(/\/$/, '')}`;

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly manager = new UserManager({
    authority: environment.oidcAuthority,
    client_id: environment.oidcClientId,
    response_type: 'code',
    scope: 'openid profile email',
    redirect_uri: new URL('auth/callback', document.baseURI).toString(),
    post_logout_redirect_uri: appRootUrl,
    automaticSilentRenew: true,
  });
  private readonly currentUser = new BehaviorSubject<User | null>(null);
  readonly user$ = this.currentUser.asObservable();

  async initialize(): Promise<void> {
    if (window.location.pathname.endsWith('/auth/callback')) {
      await this.manager.signinRedirectCallback();
      window.history.replaceState({}, document.title, appBasePath);
    }
    this.currentUser.next(await this.manager.getUser());
    this.manager.events.addUserLoaded((user) => this.currentUser.next(user));
    this.manager.events.addUserUnloaded(() => this.currentUser.next(null));
  }

  accessToken(): string | null {
    return this.currentUser.value?.access_token ?? null;
  }

  login(): Promise<void> {
    return this.manager.signinRedirect();
  }

  async logout(): Promise<void> {
    if (environment.oidcLogoutEndpoint) {
      await this.manager.removeUser();
      const logoutUrl = new URL(environment.oidcLogoutEndpoint);
      logoutUrl.searchParams.set('client_id', environment.oidcClientId);
      logoutUrl.searchParams.set('logout_uri', appRootUrl);
      window.location.assign(logoutUrl.toString());
      return;
    }
    return this.manager.signoutRedirect();
  }
}
