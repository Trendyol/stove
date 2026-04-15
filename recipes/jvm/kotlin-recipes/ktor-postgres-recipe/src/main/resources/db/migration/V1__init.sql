create table products
(
    id          text primary key,
    name        varchar(100)   not null,
    price       numeric(10, 2) not null,
    category_id integer        not null,
    version     bigint         not null default 0
);
