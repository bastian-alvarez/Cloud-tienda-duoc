import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { environment } from './environment';

export interface Order {
  id: number;
  customerEmail: string;
  sku: string;
  quantity: number;
  total: number;
  status: string;
  createdAt: string;
}

export interface CreateOrder {
  customerEmail: string;
  sku: string;
  quantity: number;
  unitPrice: number;
}

@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly http = inject(HttpClient);
  private readonly endpoint = `${environment.apiBaseUrl}/orders`;

  list(): Observable<Order[]> {
    return this.http.get<Order[]>(this.endpoint);
  }

  create(order: CreateOrder): Observable<Order> {
    return this.http.post<Order>(this.endpoint, order);
  }
}

