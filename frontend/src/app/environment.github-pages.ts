export const environment = {
  apiBaseUrl: 'https://s8vtyx4ibc.execute-api.us-east-1.amazonaws.com/api',
  oidcAuthority: 'https://cognito-idp.us-east-1.amazonaws.com/us-east-1_cwXvEtRtQ',
  oidcClientId: '5th98rm9ifcsmv2r3le3all0ta',
  oidcLogoutEndpoint: 'https://tiendaduoc-sqs-aurora-975050362354.auth.us-east-1.amazoncognito.com/logout',
  appDescription: 'Los pedidos se guardan en Aurora PostgreSQL y el procesamiento asíncrono usa SQS y Lambda.',
  architectureLabel: 'API Gateway · Cognito · Aurora · SQS · Lambda',
};
