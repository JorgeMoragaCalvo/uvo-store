const SIZES = { sm: 'h-4 w-4 border-2', md: 'h-8 w-8 border-2', lg: 'h-12 w-12 border-[3px]' } as const

export default function Loading({ size = 'md' }: { size?: keyof typeof SIZES }) {
  return (
    <div className="flex items-center justify-center p-4">
      <div className={`animate-spin rounded-full border-gray-400 border-t-primary ${SIZES[size]}`} />
    </div>
  )
}
