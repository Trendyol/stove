import type { RunStatus, SpanStatus, Status } from "./types";

type JsonRecord = Record<string, unknown>;
export type Validator<T> = (value: unknown) => value is T;
export type ObjectSchema<T> = { [Field in keyof T]-?: Validator<T[Field]> };

export const isString = (value: unknown): value is string => typeof value === "string";
export const isNumber = (value: unknown): value is number =>
  typeof value === "number" && Number.isFinite(value);
export const isBoolean = (value: unknown): value is boolean => typeof value === "boolean";
export const isNullableString = (value: unknown): value is string | null =>
  value === null || isString(value);
export const isNullableNumber = (value: unknown): value is number | null =>
  value === null || isNumber(value);
export const isStringArray = (value: unknown): value is string[] =>
  Array.isArray(value) && value.every(isString);
export const isStringRecord = (value: unknown): value is Record<string, string> =>
  isRecord(value) && Object.values(value).every(isString);
export const isStatus = (value: unknown): value is Status =>
  value === "RUNNING" || value === "PASSED" || value === "FAILED" || value === "ERROR";

export const isSpanStatus = (value: unknown): value is SpanStatus =>
  value === "OK" || value === "ERROR" || value === "UNSET";

export function isRecord(value: unknown): value is JsonRecord {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function arrayOf<T>(validate: Validator<T>): Validator<T[]> {
  return (value): value is T[] => Array.isArray(value) && value.every(validate);
}

/** Require every declared field, while allowing newer servers to add fields. */
export function object<T>(schema: ObjectSchema<T>): Validator<T> {
  return (value): value is T =>
    isRecord(value) &&
    Object.entries<Validator<unknown>>(schema).every(([field, validate]) => validate(value[field]));
}

export const isRunStatus = (value: unknown): value is RunStatus =>
  value === "RUNNING" || value === "PASSED" || value === "FAILED";

export function nullable<T>(validate: Validator<T>): Validator<T | null> {
  return (value): value is T | null => value === null || validate(value);
}
