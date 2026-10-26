import React, { type FC } from "react";

interface FileRowProps {
  path: string;
  renamedFrom?: string;
  onOpen(path: string): void;
}

/** A changed file in a list. */
export const FileRow: FC<FileRowProps> = ({ path, renamedFrom, onOpen }) => {
  const name = path.split("/").pop() ?? path;
  return (
    <li className="file-row" onDoubleClick={() => onOpen(path)}>
      <strong>{name}</strong>
      {renamedFrom && <em title={`Renamed from ${renamedFrom}`}>← {renamedFrom}</em>}
    </li>
  );
};

export default function List<T extends string>({ items }: { items: T[] }) {
  return <ul>{items.map((item) => <FileRow key={item} path={item} onOpen={console.log} />)}</ul>;
}
