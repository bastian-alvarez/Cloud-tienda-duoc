import { AsyncPipe, CurrencyPipe, DatePipe, NgFor, NgIf } from '@angular/common';
import { Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { AuthService } from './auth.service';
import { environment } from './environment';
import { CreateOrder, Order, OrdersService } from './orders.service';

@Component({
  selector: 'app-root',
  standalone: true,
  imports: [AsyncPipe, CurrencyPipe, DatePipe, FormsModule, NgFor, NgIf],
  template: `
    <main class="shell">
      <header class="topbar">
        <div>
          <p class="eyebrow">CLOUD NATIVE · DUOC UC</p>
          <h1>TiendaDuoc</h1>
        </div>
        <ng-container *ngIf="auth.user$ | async as user; else signedOut">
          <div class="session">
            <span>{{ user.profile.email || user.profile.preferred_username }}</span>
            <button class="button secondary" type="button" (click)="logout()">Cerrar sesión</button>
          </div>
        </ng-container>
        <ng-template #signedOut>
          <button class="button" type="button" (click)="login()">Ingresar con IDaaS</button>
        </ng-template>
      </header>

      <section class="intro">
        <div>
          <p class="eyebrow">PEDIDOS ASÍNCRONOS</p>
          <h2>Una compra, varios procesos independientes.</h2>
          <p>{{ environment.appDescription }}</p>
        </div>
        <div class="badge">{{ environment.architectureLabel }}</div>
      </section>

      <section *ngIf="auth.user$ | async; else loginHint" class="content">
        <form class="card form" (ngSubmit)="createOrder()">
          <div class="section-title">
            <div>
              <p class="eyebrow">NUEVO PEDIDO</p>
              <h3>Registrar compra</h3>
            </div>
          </div>
          <label>Correo del cliente
            <input name="email" type="email" [(ngModel)]="form.customerEmail" required maxlength="254">
          </label>
          <div class="fields">
            <label>SKU
              <input name="sku" [(ngModel)]="form.sku" required maxlength="80">
            </label>
            <label>Cantidad
              <input name="quantity" type="number" [(ngModel)]="form.quantity" required min="1">
            </label>
            <label>Precio unitario
              <input name="unitPrice" type="number" [(ngModel)]="form.unitPrice" required min="0.01" step="0.01">
            </label>
          </div>
          <button class="button" type="submit" [disabled]="saving">
            {{ saving ? 'Enviando…' : 'Crear pedido' }}
          </button>
          <p *ngIf="error" class="feedback error" role="alert">{{ error }}</p>
          <p *ngIf="notice" class="feedback success" role="status">{{ notice }}</p>
        </form>

        <section class="card">
          <div class="section-title">
            <div>
              <p class="eyebrow">PROCESAMIENTO</p>
              <h3>Pedidos recientes</h3>
            </div>
            <button class="button secondary" type="button" (click)="refresh()">Actualizar</button>
          </div>
          <p *ngIf="orders.length === 0" class="empty">Todavía no hay pedidos.</p>
          <div class="table-wrap" *ngIf="orders.length > 0">
            <table>
              <thead><tr><th>Pedido</th><th>Producto</th><th>Total</th><th>Estado de pago</th><th>Creado</th></tr></thead>
              <tbody>
                <tr *ngFor="let order of orders">
                  <td>#{{ order.id }}<small>{{ order.customerEmail }}</small></td>
                  <td>{{ order.sku }} × {{ order.quantity }}</td>
                  <td>{{ order.total | currency:'CLP':'symbol-narrow':'1.0-0' }}</td>
                  <td><span class="status" [class.paid]="order.status === 'PAGADO' || order.status === 'COMPLETADO'">{{ order.status }}</span></td>
                  <td>{{ order.createdAt | date:'short' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </section>
      <ng-template #loginHint>
        <section class="card login-hint">
          <h3>Acceso protegido</h3>
          <p>Inicia sesión con el proveedor OIDC para registrar y consultar pedidos.</p>
        </section>
      </ng-template>
      <footer>Demostración académica · pagos e inventario simulados · no usar con datos reales</footer>
    </main>
  `,
})
export class AppComponent implements OnInit {
  readonly environment = environment;
  readonly auth = inject(AuthService);
  private readonly ordersService = inject(OrdersService);
  orders: Order[] = [];
  form: CreateOrder = {
    customerEmail: '',
    sku: 'POLERA-DUOC-M',
    quantity: 1,
    unitPrice: 9990,
  };
  saving = false;
  error = '';
  notice = '';

  ngOnInit(): void {
    this.auth.user$.subscribe((user) => {
      if (user) {
        this.refresh();
      } else {
        this.orders = [];
      }
    });
  }

  login(): void {
    void this.auth.login();
  }

  logout(): void {
    void this.auth.logout();
  }

  refresh(): void {
    this.ordersService.list().subscribe({
      next: (orders) => {
        this.orders = orders;
        this.error = '';
      },
      error: (error: unknown) => this.showError(error),
    });
  }

  createOrder(): void {
    this.saving = true;
    this.error = '';
    this.notice = '';
    this.ordersService.create(this.form).subscribe({
      next: (order) => {
        this.notice = `Pedido #${order.id} creado. El pago y el inventario se procesan en segundo plano.`;
        this.saving = false;
        this.refresh();
      },
      error: (error: unknown) => {
        this.saving = false;
        this.showError(error);
      },
    });
  }

  private showError(error: unknown): void {
    if (error instanceof HttpErrorResponse) {
      this.error = error.status === 401 || error.status === 403
        ? 'La sesión no está autorizada. Vuelve a ingresar.'
        : error.error?.detail || `No se pudo completar la solicitud (HTTP ${error.status}).`;
      return;
    }
    this.error = 'Ocurrió un error inesperado.';
  }
}
