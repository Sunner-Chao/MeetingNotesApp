interface BrandMarkProps {
  size?: number;
}

export function BrandMark({ size = 44 }: BrandMarkProps) {
  return (
    <img
      className="brand-mark"
      src={`${import.meta.env.BASE_URL}icons/icon-512.png`}
      width={size}
      height={size}
      alt="智悟本"
    />
  );
}
