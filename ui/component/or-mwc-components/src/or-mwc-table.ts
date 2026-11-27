/*
 * Copyright 2026, OpenRemote Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
import { css, html, type TemplateResult, unsafeCSS } from "lit";
import { OrElement } from "@openremote/or-element";
import { customElement, property, state } from "lit/decorators.js";
import { classMap } from "lit/directives/class-map.js";
import { until } from "lit/directives/until.js";
import { MDCDataTable } from "@material/data-table";
import { when } from "lit/directives/when.js";
import { DefaultColor3, DefaultColor2, DefaultColor1 } from "@openremote/core";
import { i18next } from "@openremote/or-translate";
import { InputType, type OrInputChangedEvent } from "./or-mwc-input";
import { styleMap } from "lit/directives/style-map.js";
import moment from "moment";

const dataTableStyle = require("@material/data-table/dist/mdc.data-table.css");

// language=CSS
const style = css`
  :host {
    width: 100%;
  }

  :host([hidden]) {
    display: none;
  }

  .mdc-data-table {
    width: 100%;
    overflow: auto;
    max-height: 500px;
  }

  .mdc-data-table__paginated {
    overflow: hidden;
    max-height: 700px;
    justify-content: space-between;
  }

  .mdc-data-table__fullheight {
    height: 100%;
    max-height: none !important;
  }

  /* first column should be sticky*/
  .mdc-data-table.has-sticky-first-column tr th:first-of-type,
  .mdc-data-table.has-sticky-first-column tr td:first-of-type {
    z-index: 1;
    position: sticky;
    left: 0;
    background-color: ${unsafeCSS(DefaultColor2)};
  }

  .mdc-data-table.has-sticky-first-column tr th:first-of-type {
    z-index: 2;
  }

  thead th {
    box-shadow: 0 1px 0 0 rgb(229, 229, 229);
  }

  .mdc-data-table.has-sticky-first-column tr td:first-of-type {
    box-shadow: 1px 0 0 0 rgb(229, 229, 229);
  }

  thead th:first-of-type {
    box-shadow: 1px 1px 0 0 rgb(229, 229, 229);
  }

  th {
    position: sticky;
    top: 0;
    background-color: ${unsafeCSS(DefaultColor1)};
  }

  th,
  td {
    cursor: default;
  }

  .mdc-data-table__header-cell {
    font-weight: bold;
    color: ${unsafeCSS(DefaultColor3)};
    font-size: 14px;
  }

  .mdc-data-table__pagination-rows-per-page-select {
    /*min-width: 112px;*/
  }

  .mdc-data-table__pagination {
    min-height: 64px;
  }

  .mdc-data-table__cell--clickable {
    cursor: pointer;
  }

  .sort-button {
    padding-right: 0;
    border: none;
    color: ${unsafeCSS(DefaultColor3)};
    background-color: transparent;
    cursor: pointer;
  }

  .sort-button-reverse {
    padding-left: 0;
    border: none;
    color: ${unsafeCSS(DefaultColor3)};
    cursor: pointer;
  }

  .sortable {
    flex-direction: row;
    cursor: pointer;
  }

  .sortable-reverse {
    flex-direction: row-reverse;
    cursor: pointer;
  }

  .hidden {
    visibility: hidden;
  }

  #column-1 {
    width: var(--or-mwc-table-column-width-1, unset);
  }
  #column-2 {
    width: var(--or-mwc-table-column-width-2, unset);
  }
  #column-3 {
    width: var(--or-mwc-table-column-width-3, unset);
  }
  #column-4 {
    width: var(--or-mwc-table-column-width-4, unset);
  }
  #column-5 {
    width: var(--or-mwc-table-column-width-5, unset);
  }
  #column-6 {
    width: var(--or-mwc-table-column-width-6, unset);
  }
  #column-7 {
    width: var(--or-mwc-table-column-width-7, unset);
  }
  #column-8 {
    width: var(--or-mwc-table-column-width-8, unset);
  }
  #column-9 {
    width: var(--or-mwc-table-column-width-9, unset);
  }
  #column-10 {
    width: var(--or-mwc-table-column-width-10, unset);
  }

  @media screen and (max-width: 768px) {
    .hide-mobile {
      display: none;
    }
  }
`;

export interface TableConfig {
  columnFilter?: string[];
  stickyFirstColumn?: boolean;
  fullHeight?: boolean;
  pagination?: {
    enable?: boolean;
    options?: number[];
  };
  multiSelect?: boolean;
}

export interface TableColumn {
  title?: string;
  isNumeric?: boolean;
  hideMobile?: boolean;
  isSortable?: boolean;
}

export type TableContent = string | number | Date | TemplateResult | SortableTableTemplate;

export interface SortableTableTemplate {
  template: TemplateResult;
  sortValue: string | number;
}

function isSortableTableTemplate(content: TableContent | undefined): content is SortableTableTemplate {
  return typeof content === "object" && content !== null && "sortValue" in content;
}

export interface TableRow {
  content?: TableContent[];
  clickable?: boolean;
}

export interface OrMwcTableRowClickDetail {
  index: number;
}

export interface OrMwcTableRowSelectDetail extends OrMwcTableRowClickDetail {
  state: boolean;
}

export class OrMwcTableRowClickEvent extends CustomEvent<OrMwcTableRowClickDetail> {
  public static readonly NAME = "or-mwc-table-row-click";

  constructor(index: number) {
    super(OrMwcTableRowClickEvent.NAME, {
      detail: {
        index,
      },
      bubbles: true,
      composed: true,
    });
  }
}

export class OrMwcTableRowSelectEvent extends CustomEvent<OrMwcTableRowSelectDetail> {
  public static readonly NAME = "or-mwc-table-row-select";

  constructor(index: number, state: boolean) {
    super(OrMwcTableRowSelectEvent.NAME, {
      detail: {
        index,
        state,
      },
      bubbles: true,
      composed: true,
    });
  }
}

@customElement("or-mwc-table")
export class OrMwcTable extends OrElement {
  static get styles() {
    return [
      css`
        ${unsafeCSS(dataTableStyle)}
      `,
      style,
    ];
  }

  @property({ type: Array })
  public columns?: TableColumn[] | string[];

  @property({ type: Object }) // to manually control HTML
  protected columnsTemplate?: TemplateResult;

  @property({ type: Array })
  public rows?: TableRow[] | string[][];

  @property({ type: Object }) // to manually control HTML (requires td and tr elements)
  protected rowsTemplate?: TemplateResult;

  @property({ type: Array })
  protected config: TableConfig = {
    columnFilter: [],
    stickyFirstColumn: true,
    fullHeight: false,
    pagination: {
      enable: false,
    },
  };

  @property({ type: Number })
  protected paginationIndex: number = 0;

  @property({ type: Number })
  public paginationSize: number = 10;

  @property({ type: Number })
  public totalCount?: number;

  @state()
  protected _dataTable?: MDCDataTable;

  @property({ type: String })
  protected sortDirection: "ASC" | "DESC" = "ASC";

  @property({ type: Number })
  protected sortIndex?: number = -1;

  @property({ type: Array })
  public selectedRows?: TableRow[] | string[][] | any[] = [];

  /* ------------------- */

  protected firstUpdated(changedProperties: Map<string, any>) {
    const elem = this.shadowRoot!.querySelector(".mdc-data-table");
    this._dataTable = new MDCDataTable(elem!);
  }

  protected updated(changedProperties: Map<string, any>) {
    if (
      (changedProperties.has("paginationIndex") || changedProperties.has("paginationSize")) &&
      this.config.pagination?.enable
    ) {
      const elem = this._dataTable
        ? this._dataTable.root.children[0]
        : this.shadowRoot!.querySelector(".mdc-data-table__table-container");

      // Using an observer to prevent forced reflow / DOM measurements; prevents blocking the thread
      const observer = new IntersectionObserver((entries, observer) => {
        (entries[0].target as HTMLElement).scrollTop = 0;
        observer.unobserve(entries[0].target);
      });
      observer.observe(elem!);

      // Reset selected rows properties
      this.selectedRows = [];
    }
  }

  protected render() {
    const tableClasses = {
      "mdc-data-table": true,
      "mdc-data-table__paginated": !!this.config.pagination?.enable,
      "mdc-data-table__fullheight": !!this.config.fullHeight,
      "has-sticky-first-column": !!this.config.stickyFirstColumn,
    };
    // Only show pagination if enabled in config, and when the amount of rows doesn't fit on the page using the
    // smallest page size available; comparing against the smallest size (rather than the selected one) ensures the
    // rows-per-page control stays visible after selecting a page size larger than the row count.
    const paginationOptions = this.config.pagination?.options || [10, 25, 100];
    const minPageSize = Math.min(...paginationOptions, this.paginationSize);
    const showPagination =
      this.config.pagination?.enable &&
      (!!this.rowsTemplate || (this.totalCount ?? this.rows?.length ?? 0) > minPageSize);
    const tableWidth = this.shadowRoot?.firstElementChild?.clientWidth;
    return html`
      <div class="${classMap(tableClasses)}">
        <div class="mdc-data-table__table-container" style="flex: 1;">
          <table class="mdc-data-table__table">
            <!-- Header row that normally includes entries like 'id' and 'name'. You can use either a template or a list of columns -->
            ${when(
              this.columnsTemplate,
              () => this.columnsTemplate,
              () => {
                return this.columns
                  ? html`
                      <thead>
                        <tr class="mdc-data-table__header-row">
                          ${this.columns.map((column: TableColumn | string, index: number) => {
                            const styles = {
                              width: this.getColumnWidth(index, this.columns, tableWidth),
                              maxWidth: this.getMaxColumnWidth(index, this.columns, tableWidth),
                            } as any;
                            if (index === 0 && this.config.multiSelect) {
                              const allSelected =
                                this.rows && this.rows.length > 0 && this.selectedRows?.length === this.rows?.length;
                              const indeterminate = !allSelected && this.selectedRows && this.selectedRows.length > 0;
                              return html` <th
                                  class="mdc-data-table__header-cell mdc-data-table__header-cell--checkbox"
                                  role="columnheader"
                                  scope="col"
                                >
                                  <or-mwc-input
                                    type="${InputType.CHECKBOX}"
                                    id="checkbox"
                                    .indeterminate="${indeterminate}"
                                    .value="${allSelected}"
                                    @or-mwc-input-changed="${(ev: OrInputChangedEvent) => this.onCheckChanged(ev, ev.detail.value, "all")}"
                                  >
                                  </or-mwc-input>
                                </th>
                                ${
                                  typeof column === "string"
                                    ? html`
                                        <th
                                          class="mdc-data-table__header-cell ${this.config.multiSelect ? "mdc-data-table__header-cell" : ""}"
                                          id="column-${index + 1}"
                                          role="columnheader"
                                          scope="col"
                                          title="${column}"
                                        >
                                          column
                                        </th>
                                      `
                                    : html`
                                        <th
                                          class="mdc-data-table__header-cell ${classMap({
                                            "mdc-data-table__cell--numeric": !!column.isNumeric,
                                            "hide-mobile": !!column.hideMobile,
                                            "mdc-data-table__header-cell--with-sort": !!column.isSortable,
                                          })}"
                                          role="columnheader"
                                          scope="col"
                                          title="${column.title}"
                                          data-column-id="${column.title}"
                                          @click="${(ev: MouseEvent) => (column.isSortable! ? this.onColumnSort(ev, index, this.sortDirection!) : "")}"
                                        >
                                          ${!column.isSortable ? column.title : until(this.getSortHeader(index, column.title!, this.sortDirection!), html`${i18next.t("loading")}`)}
                                        </th>
                                      `
                                }`;
                            }
                            return typeof column === "string"
                              ? html`
                                  <th
                                    class="mdc-data-table__header-cell ${this.config.multiSelect ? "mdc-data-table__header-cell" : ""}"
                                    id="column-${index + 1}"
                                    role="columnheader"
                                    scope="col"
                                    title="${column}"
                                  >
                                    ${column}
                                  </th>
                                `
                              : html`
                                  <th
                                    class="mdc-data-table__header-cell ${classMap({
                                      "mdc-data-table__cell--numeric": !!column.isNumeric,
                                      "hide-mobile": !!column.hideMobile,
                                      "mdc-data-table__header-cell--with-sort": !!column.isSortable,
                                    })}"
                                    style="${styleMap(styles)}"
                                    role="columnheader"
                                    scope="col"
                                    title="${column.title}"
                                    data-column-id="${column.title}"
                                    @click="${(ev: MouseEvent) => (column.isSortable! ? this.onColumnSort(ev, index, this.sortDirection ? this.sortDirection : "ASC") : "")}"
                                  >
                                    ${!column.isSortable ? column.title : until(this.getSortHeader(index, column.title!, this.sortDirection!, !!column.isNumeric), html`${i18next.t("loading")}`)}
                                  </th>
                                `;
                          })}
                        </tr>
                      </thead>
                    `
                  : undefined;
              }
            )}
            <!-- Table content, where either the template or an array of rows is displayed -->
            <tbody class="mdc-data-table__content">
              ${when(
                this.rowsTemplate,
                () => {
                  if (this.config.pagination?.enable) {
                    // if paginated, filter out the rows by index by manually collecting a list of <tr> elements.
                    this.updateComplete.then(async () => {
                      const elem = await this.getTableElem(false);
                      const rows = elem?.querySelectorAll("tr");
                      rows?.forEach((row, index) => {
                        const hidden =
                          (index <= this.paginationIndex * this.paginationSize ||
                            index > this.paginationIndex * this.paginationSize + this.paginationSize) &&
                          !row.classList.contains("mdc-data-table__header-row");
                        row.style.display = hidden ? "none" : "table-row";
                      });
                    });
                  }
                  return html`${this.rowsTemplate}`;
                },
                () => {
                  return this.rows
                    ? (this.rows as any[])
                        .filter(
                          (row, index) =>
                            !this.config.pagination?.enable ||
                            (index >= this.paginationIndex * this.paginationSize &&
                              index < this.paginationIndex * this.paginationSize + this.paginationSize)
                        )
                        .map((item: TableRow | string[]) => {
                          const content: TableContent[] | undefined = Array.isArray(item)
                            ? item
                            : (item as TableRow).content;
                          return html`
                            <tr class="mdc-data-table__row" @click="${(ev: MouseEvent) => this.onRowClick(ev, item)}">
                              ${content?.map((cell: TableContent, index: number) => {
                                const classes = {
                                  "mdc-data-table__cell": true,
                                  "mdc-data-table__cell--numeric": typeof cell === "number",
                                  "mdc-data-table__cell--clickable": (!Array.isArray(item) &&
                                    (item as TableRow).clickable)!,
                                  "hide-mobile": (this.columns &&
                                    typeof this.columns[index] !== "string" &&
                                    (this.columns[index] as TableColumn).hideMobile)!,
                                };
                                const styles = {
                                  maxWidth: this.getMaxColumnWidth(index, this.columns, tableWidth),
                                } as any;
                                return html`
                                  ${when(
                                    index === 0 && this.config.multiSelect,
                                    () => html`
                                      <td class="mdc-data-table__cell mdc-data-table__cell--checkbox">
                                        <div class="">
                                          <or-mwc-input
                                            type="${InputType.CHECKBOX}"
                                            id="checkbox-${index}"
                                            @or-mwc-input-changed="${(ev: OrInputChangedEvent) => this.onCheckChanged(ev, ev.detail.value, "single", item)}"
                                            .value="${this.selectedRows?.includes(item)}"
                                          ></or-mwc-input>
                                        </div>
                                      </td>
                                    `
                                  )}
                                  <td
                                    class="${classMap(classes)}"
                                    title="${typeof cell === "object" ? "" : cell}"
                                    style="${styleMap(styles)}"
                                  >
                                    ${until(this.getTableContent(cell))}
                                  </td>
                                `;
                              })}
                            </tr>
                          `;
                        })
                    : undefined;
                }
              )}
            </tbody>
          </table>
        </div>
        <!-- Pagination HTML, shown on the bottom right. Same as Material Design spec -->
        ${when(showPagination, () => {
          const options = paginationOptions;
          return html`
            <div class="mdc-data-table__pagination">
              <div class="mdc-data-table__pagination-trailing">
                <div class="mdc-data-table__pagination-rows-per-page">
                  <div class="mdc-data-table__pagination-rows-per-page-label">${i18next.t("rowsPerPage")}</div>
                  <or-mwc-input
                    class="mdc-data-table__pagination-rows-per-page-select"
                    .type="${InputType.SELECT}"
                    compact
                    comfortable
                    outlined
                    .readonly="${options.length === 1}"
                    .value="${this.paginationSize}"
                    .options="${options}"
                    @or-mwc-input-changed="${(ev: OrInputChangedEvent) => {
                      this.paginationSize = ev.detail.value;
                      this.paginationIndex = 0;
                    }}"
                  ></or-mwc-input>
                </div>
                ${until(this.getPaginationControls(), html`${i18next.t("loading")}`)}
              </div>
            </div>
          `;
        })}
      </div>
    `;
  }

  /**
   * Function that is responsible for formatting the table content.
   * For example; dates should be formatted differently than strings, numbers and HTML templates.
   */
  protected getTableContent(content: TableContent): TemplateResult | string {
    if (typeof content === "string" || typeof content === "number") {
      return content as string;
    } else if (content instanceof Date) {
      return moment(content).format("lll");
    } else if (isSortableTableTemplate(content)) {
      return content.template;
    } else {
      return content;
    }
  }

  /**
   * "When user clicks on a row", it dispatches an {@link OrMwcTableRowClickEvent} to the parent component / listeners.
   */
  protected onRowClick(ev: MouseEvent, item: TableRow | string[]) {
    if (this.config.multiSelect) {
      const elem = ev.target as HTMLElement;
      if (elem.nodeName === "OR-MWC-INPUT" && elem.id.includes("checkbox")) {
        return; // if checkbox is clicked, the regular "click on row" should not trigger.
      }
    }
    this.dispatchEvent(new OrMwcTableRowClickEvent((this.rows as any[]).indexOf(item)));
  }

  /**
   * "When user clicks on a checkbox" when multiselect is enabled,
   * it dispatches an {@link OrMwcTableRowSelectEvent} to the parent component / listeners.
   */
  protected onCheckChanged(event: Event, checked: boolean, type: "all" | "single", item?: any) {
    event.stopPropagation();
    if (type === "all") {
      if (checked) {
        this.selectedRows = this.rows;
      } else {
        this.selectedRows = [];
      }
      // Dispatch events to parent component
      this.rows?.forEach((_, index) => this.dispatchEvent(new OrMwcTableRowSelectEvent(index, checked)));
    } else {
      if (checked) {
        if (this.selectedRows === undefined) {
          this.selectedRows = [item];
        } else if (this.selectedRows.indexOf(item) === -1) {
          this.selectedRows.push(item);
          this.requestUpdate("selectedRows");
        }
      } else {
        this.selectedRows = this.selectedRows?.filter((e: TableRow) => e !== item);
      }
      // Dispatch events to parent component
      const index = this.rows?.indexOf(item);
      if (index !== undefined && index > -1) {
        this.dispatchEvent(new OrMwcTableRowSelectEvent(index, checked));
      }
    }
  }

  /**
   * Event handling function for when users try to sort the rows within a column.
   */
  async onColumnSort(ev: MouseEvent, index: number, sortDirection: "ASC" | "DESC") {
    if (this.config.multiSelect) {
      const elem = ev.target as HTMLElement;
      if (elem.nodeName === "OR-MWC-INPUT" && elem.id.includes("checkbox")) {
        return; // if checkbox is clicked, sort should not trigger.
      }
    }
    const newDirection = sortDirection == "ASC" ? "DESC" : "ASC";
    this.sortDirection = newDirection;
    this.sortIndex = index;
    if (this.rows && this.rows.length > 0) {
      // If string array of rows,
      if (Array.isArray(this.rows[0])) {
        (this.rows as string[][]).sort((a: string[], b: string[]) => {
          return this.sortPrimitiveRows(a, b, index, newDirection);
        });
      }
      // Else if row is TableRow
      else {
        (this.rows as TableRow[]).sort((a: TableRow, b: TableRow) => {
          return this.sortObjectRows(a, b, index, newDirection);
        });
      }
    }
  }

  /**
   * Sorts either numbers incrementally or strings alphabetically depending on the sorting direction.
   */
  protected sortPrimitiveRows(
    rowA: string[] | number[],
    rowB: string[] | number[],
    cIndex: number,
    sortDirection: "ASC" | "DESC"
  ): number {
    const cellA = rowA[cIndex];
    const cellB = rowB[cIndex];
    if (!cellA && !cellB) {
      return 0;
    } else if (!cellA || cellA.toString().length === 0) {
      return sortDirection === "DESC" ? 1 : -1;
    } else if (!cellB || cellB.toString().length === 0) {
      return sortDirection === "DESC" ? -1 : 1;
    } else {
      if (typeof cellA === "string" && typeof cellB === "string") {
        if (sortDirection === "DESC") {
          return (cellB as string).localeCompare(cellA as string, "en", { numeric: true });
        } else {
          return (cellA as string).localeCompare(cellB as string, "en", { numeric: true });
        }
      } else if (typeof cellA === "number" && typeof cellB === "number") {
        if (sortDirection === "DESC") {
          return cellA > cellB ? -1 : 1;
        } else {
          return cellA > cellB ? 1 : -1;
        }
      } else {
        console.warn("sortPrimitiveRows() was called using neither a number nor a string.");
        return 1;
      }
    }
  }

  /**
   * Function that handles several sorts of table content; from dates to HTML templates.
   * */
  protected sortObjectRows(rowA: TableRow, rowB: TableRow, cIndex: number, sortDirection: "ASC" | "DESC"): number {
    const cellA = this.getSortValue(rowA.content?.[cIndex]);
    const cellB = this.getSortValue(rowB.content?.[cIndex]);
    const isSortable = (content: any) => typeof content === "string" || typeof content === "number";
    const isTemplate = (content: any) => content.strings !== undefined && content.values !== undefined;

    if (!cellA || !cellB || isSortable(cellA) || isSortable(cellB)) {
      return this.sortPrimitiveRows([cellA as string], [cellB as string], 0, sortDirection);
    } else if (cellA instanceof Date && cellB instanceof Date) {
      return this.sortDateRows(cellA, cellB, 0, sortDirection);
    } else if (isTemplate(cellA) || isTemplate(cellB)) {
      return this.sortTemplateRows(cellA, cellB, 0, sortDirection);
    }
    return 1;
  }

  protected getSortValue(content: TableContent | undefined): Exclude<TableContent, SortableTableTemplate> | undefined {
    return isSortableTableTemplate(content) ? content.sortValue : content;
  }

  protected sortDateRows(dateA: Date, dateB: Date, cIndex: number, sortDirection: "ASC" | "DESC"): number {
    if (sortDirection === "DESC") {
      return dateA > dateB ? -1 : 1;
    } else {
      return dateA > dateB ? 1 : -1;
    }
  }

  /**
   * Uses the {@link TemplateResult} content to identify and analyze the content.
   * See the documentation here; https://lit.dev/docs/v2/api/templates/#TemplateResult
   * Sometimes it cannot analyze the HTML; if so, it will retain the same order.
   */
  protected sortTemplateRows(cellA: any, cellB: any, cIndex: number, sortDirection: "ASC" | "DESC"): number {
    const valueA: string | undefined = (cellA.values as any[])
      .filter((v) => typeof v === "string" || typeof v === "number")
      .map((v) => v.toString())?.[0];
    const valueB: string | undefined = (cellB.values as any[])
      .filter((v) => typeof v === "string" || typeof v === "number")
      .map((v) => v.toString())?.[0];
    if (valueA !== undefined && valueB !== undefined) {
      return this.sortPrimitiveRows([valueA], [valueB], 0, sortDirection);
    } else {
      return 1;
    }
  }

  // HTML for the sort header, that adds click functionality and a up/down arrow.
  async getSortHeader(
    index: number,
    title: string,
    sortDirection: "ASC" | "DESC",
    arrowOnLeft = false
  ): Promise<TemplateResult> {
    this.sortIndex === -1 ? (this.sortIndex = index) : "";
    return html`
      <div class="mdc-data-table__header-cell-wrapper ${arrowOnLeft ? "sortable-reverse" : "sortable"}">
        <div class="mdc-data-table__header-cell-label">${title}</div>
        <button
          class="mdc-icon-button material-icons ${arrowOnLeft ? "sort-button-reverse" : "sort-button"} ${this.sortIndex === index ? "" : "hidden"}"
          aria-label="Sort by ${title}"
          aria-describedby="${title}-status-label"
          aria-hidden="${!(this.sortIndex === index)}"
        >
          <or-icon icon="${sortDirection == "ASC" ? "arrow-up" : "arrow-down"}"></or-icon>
        </button>
        <div class="mdc-data-table__sort-status-label" aria-hidden="true" id="${title}-status-label"></div>
      </div>
    `;
  }

  // HTML for the controls on the bottom of the table.
  // Includes basic pagination for browsing pages, with calculations of where to go.
  async getPaginationControls(): Promise<TemplateResult> {
    return this.renderPaginationControls(this.paginationIndex, (page) => {
      this.paginationIndex = page;
    });
  }

  /**
   * Renders the bottom pagination controls for the given (zero-based) current page. Navigation is delegated to
   * {@link goToPage} so subclasses can drive pagination differently (e.g. this component's local
   * `paginationIndex`, or server-side pagination via an event).
   */
  protected async renderPaginationControls(page: number, goToPage: (page: number) => void): Promise<TemplateResult> {
    const max: number = await this.getRowCount();
    const start: number = page * this.paginationSize + 1;
    const end: number = Math.min(page * this.paginationSize + this.paginationSize, max);
    const isFirst: boolean = page === 0;
    const isLast: boolean = end >= max;
    const lastPage: number = Math.max(0, Math.ceil(max / this.paginationSize) - 1);
    return html`
      <div class="mdc-data-table__pagination-navigation">
        <div class="mdc-data-table__pagination-total">
          <span>${start}-${end} of ${max}</span>
        </div>
        <or-mwc-input
          class="mdc-data-table__pagination-button"
          .type="${InputType.BUTTON}"
          data-first-page="true"
          icon="page-first"
          .disabled="${isFirst}"
          @or-mwc-input-changed="${() => goToPage(0)}"
        ></or-mwc-input>
        <or-mwc-input
          class="mdc-data-table__pagination-button"
          .type="${InputType.BUTTON}"
          data-prev-page="true"
          icon="chevron-left"
          .disabled="${isFirst}"
          @or-mwc-input-changed="${() => goToPage(page - 1)}"
        ></or-mwc-input>
        <or-mwc-input
          class="mdc-data-table__pagination-button"
          .type="${InputType.BUTTON}"
          data-next-page="true"
          icon="chevron-right"
          .disabled="${isLast}"
          @or-mwc-input-changed="${() => goToPage(page + 1)}"
        ></or-mwc-input>
        <or-mwc-input
          class="mdc-data-table__pagination-button"
          .type="${InputType.BUTTON}"
          data-last-page="true"
          icon="page-last"
          .disabled="${isLast}"
          @or-mwc-input-changed="${() => goToPage(lastPage)}"
        ></or-mwc-input>
      </div>
    `;
  }

  // Getting the amount of rows/entries in the table.
  // Makes sure that both the rows, and rowsTemplate properties work.
  async getRowCount(wait: boolean = true, tableElem?: HTMLElement): Promise<number> {
    if (this.rows?.length) {
      return this.rows?.length;
    }
    if (!tableElem) {
      tableElem = await this.getTableElem(wait);
    }
    const rowElems = tableElem?.querySelectorAll("tr");
    return rowElems!.length;
  }

  async getTableElem(wait: boolean = false): Promise<HTMLElement | undefined> {
    if (wait) {
      await this.updateComplete;
    }
    return this._dataTable
      ? (this._dataTable.root as HTMLElement)
      : (this.shadowRoot!.querySelector(".mdc-data-table") as HTMLElement);
  }

  protected getColumnWidth(
    index: number,
    columns?: string[] | TableColumn[],
    tableWidthPx?: number
  ): string | undefined {
    return undefined;
  }

  protected getMaxColumnWidth(
    index: number,
    columns?: string[] | TableColumn[],
    tableWidthPx?: number
  ): string | undefined {
    return undefined;
  }
}
