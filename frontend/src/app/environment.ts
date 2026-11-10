export const environment = {
  apiBaseUrl: '/api',
  oidcAuthority: 'http://localhost:8180/realms/tiendaduoc',
  oidcClientId: 'tienda-web',
  oidcLogoutEndpoint: null as string | null,
  appDescription: 'Los pedidos se guardan en PostgreSQL y pago e inventario se procesan mediante RabbitMQ.',
  architectureLabel: 'Spring Boot · RabbitMQ · PostgreSQL',
};
